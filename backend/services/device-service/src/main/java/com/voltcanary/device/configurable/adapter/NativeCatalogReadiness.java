package com.voltcanary.device.configurable.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.vcl.VclException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Checks real catalog state; an intended CREATE/INDEX statement is not readiness evidence. */
final class NativeCatalogReadiness {
    private static final ObjectMapper JSON = new ObjectMapper();
    private record Column(String type, int size, int scale, boolean nullable) { }
    private record Physical(Map<String, Column> columns, Map<String, Map<Integer, String>> indexes,
                            Set<String> unique, Set<String> primary) {
        Physical() { this(new HashMap<>(), new HashMap<>(), new HashSet<>(), new HashSet<>()); }

        void index(String name, String column, int ordinal, boolean uniqueIndex, boolean primaryIndex) {
            indexes.computeIfAbsent(name, ignored -> new TreeMap<>()).put(ordinal, column);
            if (uniqueIndex) unique.add(name);
            if (primaryIndex) primary.add(column);
        }
    }
    private NativeCatalogReadiness() { }

    static void verify(Connection connection, NativeSqlLayout layout) throws SQLException {
        var physical = connection.getMetaData().getDatabaseProductName().equals("PostgreSQL")
                ? postgres(connection, layout.table()) : jdbc(connection, layout.table());
        verify(physical, layout);
    }

    private static void verify(Physical physical, NativeSqlLayout layout) {
        var columns = physical.columns();
        var expected = new HashSet<>(Set.of("record_id", "record_version", "external_key", "created_at", "updated_at"));
        layout.columns().forEach(column -> expected.add(column.name()));
        if (!columns.keySet().equals(expected)) fail();
        verifyColumn(columns.get("record_id"), "UUID", false);
        verifyColumn(columns.get("record_version"), "BIGINT", false);
        verifyColumn(columns.get("external_key"), "VARCHAR(64)", true);
        verifyColumn(columns.get("created_at"), "TIMESTAMP(6) WITH TIME ZONE", false);
        verifyColumn(columns.get("updated_at"), "TIMESTAMP(6) WITH TIME ZONE", false);
        for (var column : layout.columns()) {
            verifyColumn(columns.get(column.name()), column.sqlType(), column.nullable());
        }
        for (String required : Set.of("record_id", "external_key")) {
            if (physical.unique().stream().noneMatch(name -> List.copyOf(physical.indexes().get(name).values()).equals(List.of(required)))) fail();
        }
        if (!physical.primary().equals(Set.of("record_id"))) fail();
        for (var index : layout.indexes()) {
            var actual = physical.indexes().get(index.name());
            if (actual == null || !List.copyOf(actual.values()).equals(List.of(index.column()))) fail();
        }
    }

    private static void verifyColumn(Column column, String sqlType, boolean nullable) {
        int modifier = sqlType.indexOf('(');
        String type = modifier < 0 ? sqlType : sqlType.substring(0, modifier);
        String actual = column.type();
        boolean matches = switch (type) {
            case "BOOLEAN" -> actual.equals("BOOL") || actual.equals("BOOLEAN");
            case "BIGINT" -> actual.equals("INT8") || actual.equals("BIGINT");
            case "NUMERIC" -> actual.equals("NUMERIC") || actual.equals("DECIMAL");
            case "VARCHAR" -> actual.equals("VARCHAR") || actual.equals("CHARACTER VARYING");
            case "DOUBLE PRECISION" -> actual.equals("FLOAT8") || actual.equals("DOUBLE PRECISION");
            case "TIMESTAMP" -> actual.equals("TIMESTAMPTZ") || actual.equals("TIMESTAMP WITH TIME ZONE");
            default -> actual.equals(type);
        };
        if (!matches || column.nullable() != nullable) fail();
        if (type.equals("NUMERIC")) {
            int separator = sqlType.indexOf(',');
            int precision = Integer.parseInt(sqlType.substring(modifier + 1, separator));
            int scale = Integer.parseInt(sqlType.substring(separator + 1, sqlType.length() - 1));
            if (column.size() != precision || column.scale() != scale) fail();
        }
        if (type.equals("VARCHAR") && column.size() != Integer.parseInt(sqlType.substring(modifier + 1, sqlType.length() - 1))) fail();
    }

    static String withLockedHead(String headSql) {
        return "WITH head AS MATERIALIZED (" + headSql + "), relation AS (" + POSTGRES_RELATION + """
                WHERE n.nspname=current_schema() AND c.relkind='r' AND c.relname=
                    (SELECT 'cfg_data_' || replace(physical_id::text,'-','') FROM head)::name
                ), catalog AS (
                """ + POSTGRES_COLUMNS_AND_INDEXES + """
                ) SELECT head.*, (SELECT json_agg(catalog)::text FROM catalog) AS physical_catalog FROM head
                """;
    }

    static void verifyLockedHead(String json, NativeSqlLayout layout) throws SQLException {
        if (json == null) fail();
        var physical = new Physical();
        try {
            for (var row : JSON.readTree(json)) {
                String column = row.path("column_name").asText();
                if (!row.path("valid").asBoolean()) fail();
                if (row.path("index_name").isNull()) {
                    physical.columns().put(column, new Column(row.path("type_name").asText().toUpperCase(Locale.ROOT),
                            row.path("size").asInt(), row.path("scale").asInt(), row.path("nullable").asBoolean()));
                } else if (row.path("complete").asBoolean()) {
                    physical.index(row.path("index_name").asText(), column, row.path("position").asInt(),
                            row.path("unique_index").asBoolean(), row.path("primary_index").asBoolean());
                }
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new SQLException("Invalid PostgreSQL catalog result", error);
        }
        verify(physical, layout);
    }

    private static Physical jdbc(Connection connection, String table) throws SQLException {
        var physical = new Physical();
        var metadata = connection.getMetaData();
        String catalog = connection.getCatalog();
        String schema = connection.getSchema();
        try (var rows = metadata.getColumns(catalog, schema, table, "%")) {
            while (rows.next()) physical.columns().put(rows.getString("COLUMN_NAME"), new Column(
                    rows.getString("TYPE_NAME").toUpperCase(Locale.ROOT), rows.getInt("COLUMN_SIZE"),
                    rows.getInt("DECIMAL_DIGITS"), rows.getInt("NULLABLE") != java.sql.DatabaseMetaData.columnNoNulls));
        }
        try (var rows = metadata.getIndexInfo(catalog, schema, table, false, false)) {
            while (rows.next()) {
                String name = rows.getString("INDEX_NAME");
                String column = rows.getString("COLUMN_NAME");
                if (name == null || column == null) continue;
                physical.index(name, column, rows.getInt("ORDINAL_POSITION"), !rows.getBoolean("NON_UNIQUE"), false);
            }
        }
        try (var rows = metadata.getPrimaryKeys(catalog, schema, table)) {
            while (rows.next()) physical.primary().add(rows.getString("COLUMN_NAME"));
        }
        return physical;
    }

    private static final String POSTGRES_RELATION = """
            SELECT c.oid FROM pg_catalog.pg_class c
            JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace
            """;

    private static final String POSTGRES_COLUMNS_AND_INDEXES = """
                SELECT a.attname::text AS column_name, t.typname::text AS type_name,
                    CASE t.typname WHEN 'varchar' THEN a.atttypmod-4
                        WHEN 'numeric' THEN information_schema._pg_numeric_precision(a.atttypid,a.atttypmod) END AS size,
                    information_schema._pg_numeric_scale(a.atttypid,a.atttypmod) AS scale, NOT a.attnotnull AS nullable,
                    NULL::text AS index_name, 0 AS position, false AS unique_index, false AS primary_index,
                    true AS valid, true AS complete
                FROM relation r JOIN pg_catalog.pg_attribute a ON a.attrelid=r.oid
                    JOIN pg_catalog.pg_type t ON t.oid=a.atttypid
                WHERE a.attnum>0 AND NOT a.attisdropped
                UNION ALL
                SELECT COALESCE(a.attname::text,''), NULL, NULL, NULL, NULL, i.relname::text,
                    k.position::integer, x.indisunique, x.indisprimary, x.indisvalid AND x.indisready,
                    x.indpred IS NULL
                FROM relation r JOIN pg_catalog.pg_index x ON x.indrelid=r.oid
                    JOIN pg_catalog.pg_class i ON i.oid=x.indexrelid
                    CROSS JOIN LATERAL unnest(x.indkey) WITH ORDINALITY AS k(attribute,position)
                    LEFT JOIN pg_catalog.pg_attribute a ON a.attrelid=r.oid AND a.attnum=k.attribute
                """;

    private static Physical postgres(Connection connection, String table) throws SQLException {
        var physical = new Physical();
        try (var query = connection.prepareStatement("WITH relation AS (" + POSTGRES_RELATION
                + "WHERE n.nspname=current_schema() AND c.relname=?::name AND c.relkind='r') " + POSTGRES_COLUMNS_AND_INDEXES)) {
            query.setString(1, table);
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    String column = rows.getString("column_name");
                    String index = rows.getString("index_name");
                    if (!rows.getBoolean("valid")) fail();
                    if (index == null) {
                        physical.columns().put(column, new Column(rows.getString("type_name").toUpperCase(Locale.ROOT),
                                rows.getInt("size"), rows.getInt("scale"), rows.getBoolean("nullable")));
                    } else if (rows.getBoolean("complete")) {
                        physical.index(index, column, rows.getInt("position"), rows.getBoolean("unique_index"), rows.getBoolean("primary_index"));
                    }
                }
            }
        }
        return physical;
    }

    private static void fail() { throw new VclException("NATIVE_CATALOG_DRIFT", ""); }
}
