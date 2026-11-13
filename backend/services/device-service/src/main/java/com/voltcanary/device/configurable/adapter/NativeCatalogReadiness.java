package com.voltcanary.device.configurable.adapter;

import com.voltcanary.vcl.VclException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Checks real catalog state; an intended CREATE/INDEX statement is not readiness evidence. */
final class NativeCatalogReadiness {
    private record Column(String type, int size, int scale, boolean nullable) { }
    private NativeCatalogReadiness() { }

    static void verify(Connection connection, NativeSqlLayout layout) throws SQLException {
        var columns = new HashMap<String, Column>();
        try (var rows = connection.getMetaData().getColumns(connection.getCatalog(), connection.getSchema(), layout.table(), "%")) {
            while (rows.next()) columns.put(rows.getString("COLUMN_NAME"), new Column(
                    rows.getString("TYPE_NAME").toUpperCase(java.util.Locale.ROOT), rows.getInt("COLUMN_SIZE"),
                    rows.getInt("DECIMAL_DIGITS"), rows.getInt("NULLABLE") != java.sql.DatabaseMetaData.columnNoNulls));
        }
        var expected = new HashSet<>(Set.of("record_id", "record_version", "external_key", "created_at", "updated_at"));
        layout.columns().forEach(column -> expected.add(column.name()));
        if (!columns.keySet().equals(expected) || !"UUID".equals(columns.get("record_id").type())
                || columns.get("record_id").nullable() || columns.get("record_version").nullable()) fail();
        for (var column : layout.columns()) {
            var metadata = columns.get(column.name());
            String actual = metadata.type();
            boolean matches = switch (column.sqlType().split("\\(")[0]) {
                case "UUID" -> actual.equals("UUID");
                case "BOOLEAN" -> actual.equals("BOOL") || actual.equals("BOOLEAN");
                case "BIGINT" -> actual.equals("INT8") || actual.equals("BIGINT");
                case "NUMERIC" -> actual.equals("NUMERIC") || actual.equals("DECIMAL");
                case "VARCHAR" -> actual.equals("VARCHAR") || actual.equals("CHARACTER VARYING");
                case "DATE" -> actual.equals("DATE");
                case "DOUBLE PRECISION" -> actual.equals("FLOAT8") || actual.equals("DOUBLE PRECISION");
                case "TIMESTAMP" -> actual.equals("TIMESTAMPTZ") || actual.equals("TIMESTAMP WITH TIME ZONE");
                default -> false;
            };
            if (!matches) fail();
            if (metadata.nullable() != column.nullable()) fail();
            if (column.sqlType().startsWith("NUMERIC(")) {
                String[] sizes = column.sqlType().substring(8, column.sqlType().length() - 1).split(",");
                if (metadata.size() != Integer.parseInt(sizes[0]) || metadata.scale() != Integer.parseInt(sizes[1])) fail();
            }
            if (column.sqlType().startsWith("VARCHAR(")) {
                int length = Integer.parseInt(column.sqlType().substring(8, column.sqlType().length() - 1));
                if (metadata.size() != length) fail();
            }
        }
        var indexes = new HashMap<String, Map<Integer, String>>();
        var unique = new HashSet<String>();
        try (var rows = connection.getMetaData().getIndexInfo(connection.getCatalog(), connection.getSchema(), layout.table(), false, false)) {
            while (rows.next()) {
                String name = rows.getString("INDEX_NAME");
                String column = rows.getString("COLUMN_NAME");
                if (name == null || column == null) continue;
                indexes.computeIfAbsent(name, ignored -> new java.util.TreeMap<>()).put(rows.getInt("ORDINAL_POSITION"), column);
                if (!rows.getBoolean("NON_UNIQUE")) unique.add(name);
            }
        }
        for (String required : Set.of("record_id", "external_key")) {
            if (unique.stream().noneMatch(name -> java.util.List.copyOf(indexes.get(name).values()).equals(java.util.List.of(required)))) fail();
        }
        var primary = new HashSet<String>();
        try (var rows = connection.getMetaData().getPrimaryKeys(connection.getCatalog(), connection.getSchema(), layout.table())) {
            while (rows.next()) primary.add(rows.getString("COLUMN_NAME"));
        }
        if (!primary.equals(Set.of("record_id"))) fail();
        for (var index : layout.indexes()) {
            var actual = indexes.get(index.name());
            if (actual == null || !java.util.List.copyOf(actual.values()).equals(java.util.List.of(index.column()))) fail();
        }
        if (connection.getMetaData().getDatabaseProductName().equals("PostgreSQL")) {
            try (var query = connection.prepareStatement("""
                    SELECT 1 FROM pg_index x JOIN pg_class t ON t.oid=x.indrelid
                    JOIN pg_namespace n ON n.oid=t.relnamespace
                    WHERE n.nspname=current_schema() AND t.relname=? AND (NOT x.indisvalid OR NOT x.indisready)
                    """)) {
                query.setString(1, layout.table());
                try (var rows = query.executeQuery()) { if (rows.next()) fail(); }
            }
        }
    }

    private static void fail() { throw new VclException("NATIVE_CATALOG_DRIFT", ""); }
}
