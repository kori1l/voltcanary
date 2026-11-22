package com.voltcanary.device.configurable.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.voltcanary.device.configurable.domain.NativeDataset;
import com.voltcanary.device.configurable.domain.NativeSchema;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclType;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Trusted metadata-to-DDL compiler. Every identifier originates from a validated UUID. */
public final class NativeSqlLayout {

    public record Column(UUID fieldId, String name, String sqlType, boolean nullable) {}

    public record Index(UUID fieldId, String name, String column) {}

    private final NativeSchema schema;
    private final UUID physicalId;
    private final List<Column> columns;
    private final List<Index> indexes;

    public NativeSqlLayout(NativeSchema schema) {
        this(schema, schema.id());
    }

    public NativeSqlLayout(NativeDataset dataset) {
        this(dataset.schema(), dataset.physicalId());
    }

    private NativeSqlLayout(NativeSchema schema, UUID physicalId) {
        this.schema = schema;
        this.physicalId = physicalId;
        var result = new ArrayList<Column>();
        for (var field : schema.fields()) add(field, result);
        columns = List.copyOf(result);
        var selected = new ArrayList<Index>();
        for (var id : schema.indexes()) {
            var field = schema.field(id);
            var kind = schema.type(id).kind();
            if (
                kind == VclType.Kind.POINT ||
                kind == VclType.Kind.INTERVAL ||
                (kind == VclType.Kind.TEXT && field.path("maxLength").asInt() > 512)
            ) {
                throw new VclException("INDEX_TYPE_UNSUPPORTED", "");
            }
            selected.add(
                new Index(
                    id,
                    "cfg_ix_" + VclCanonical.sha256(physicalId + ":" + id).substring(0, 32),
                    column(id)
                )
            );
        }
        indexes = List.copyOf(selected);
    }

    public String table() {
        return "cfg_data_" + token(physicalId);
    }

    public String historyTable() {
        return "cfg_hist_" + token(physicalId);
    }

    public List<Column> columns() {
        return columns;
    }

    public List<Index> indexes() {
        return indexes;
    }

    public static String column(UUID id) {
        return "f_" + token(id);
    }

    private static String token(UUID id) {
        return id.toString().replace("-", "");
    }

    public static String identifier(String name) {
        if (!name.matches("[a-z][a-z0-9_]{0,62}")) throw new VclException(
            "PHYSICAL_IDENTIFIER_INVALID",
            ""
        );
        return "\"" + name + "\"";
    }

    public String createTable() {
        var declarations = new ArrayList<String>();
        declarations.add("record_id UUID PRIMARY KEY");
        declarations.add("record_version BIGINT NOT NULL CHECK(record_version>0)");
        declarations.add("external_key VARCHAR(64) UNIQUE");
        declarations.add(
            "created_at TIMESTAMP(6) WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL"
        );
        declarations.add(
            "updated_at TIMESTAMP(6) WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL"
        );
        columns.forEach(column ->
            declarations.add(
                identifier(column.name()) +
                    " " +
                    column.sqlType() +
                    (column.nullable() ? "" : " NOT NULL")
            )
        );
        for (var field : schema.fields()) {
            var id = UUID.fromString(field.path("id").asText());
            String prefix = column(id);
            if (schema.type(id).kind() == VclType.Kind.POINT) {
                String lon = identifier(prefix + "_lon");
                String lat = identifier(prefix + "_lat");
                declarations.add(
                    "CHECK((" +
                        lon +
                        " IS NULL AND " +
                        lat +
                        " IS NULL) OR (" +
                        lon +
                        " IS NOT NULL AND " +
                        lat +
                        " IS NOT NULL))"
                );
                declarations.add(
                    "CHECK(" +
                        lon +
                        ">=-180 AND " +
                        lon +
                        "<=180 AND " +
                        lat +
                        ">=-90 AND " +
                        lat +
                        "<=90)"
                );
            }
            if (schema.type(id).kind() == VclType.Kind.INTERVAL) {
                String start = identifier(prefix + "_start");
                String end = identifier(prefix + "_end");
                declarations.add(
                    "CHECK((" +
                        start +
                        " IS NOT NULL OR " +
                        end +
                        " IS NULL) AND (" +
                        end +
                        " IS NULL OR " +
                        end +
                        ">=" +
                        start +
                        "))"
                );
            }
        }
        return (
            "CREATE TABLE IF NOT EXISTS " +
            identifier(table()) +
            " (" +
            String.join(",", declarations) +
            ")"
        );
    }

    public String createHistory() {
        var declarations = new ArrayList<String>();
        declarations.add("record_id UUID NOT NULL");
        declarations.add("record_version BIGINT NOT NULL");
        declarations.add(
            "recorded_at TIMESTAMP(6) WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL"
        );
        declarations.add("deleted BOOLEAN NOT NULL");
        columns.forEach(column ->
            declarations.add(identifier(column.name()) + " " + column.sqlType())
        );
        declarations.add("PRIMARY KEY(record_id,record_version)");
        return (
            "CREATE TABLE IF NOT EXISTS " +
            identifier(historyTable()) +
            " (" +
            String.join(",", declarations) +
            ")"
        );
    }

    public String createIndex(Index index) {
        return (
            "CREATE INDEX IF NOT EXISTS " +
            identifier(index.name()) +
            " ON " +
            identifier(table()) +
            " (" +
            identifier(index.column()) +
            ")"
        );
    }

    private static void add(JsonNode field, List<Column> columns) {
        UUID id = UUID.fromString(field.path("id").asText());
        String name = column(id);
        var type = VclType.from(field);
        switch (type.kind()) {
            case POINT -> {
                columns.add(new Column(id, name + "_lon", "DOUBLE PRECISION", type.nullable()));
                columns.add(new Column(id, name + "_lat", "DOUBLE PRECISION", type.nullable()));
            }
            case INTERVAL -> {
                columns.add(
                    new Column(id, name + "_start", "TIMESTAMP(6) WITH TIME ZONE", type.nullable())
                );
                columns.add(new Column(id, name + "_end", "TIMESTAMP(6) WITH TIME ZONE", true));
            }
            default -> {
                String sql = switch (type.kind()) {
                    case BOOLEAN -> "BOOLEAN";
                    case INT64, DURATION -> "BIGINT";
                    case DECIMAL -> "NUMERIC(" +
                        field.path("precision").asInt() +
                        "," +
                        field.path("scale").asInt() +
                        ")";
                    case TEXT -> "VARCHAR(" + field.path("maxLength").asInt() + ")";
                    case ENUM -> "VARCHAR(256)";
                    case DATE -> "DATE";
                    case INSTANT -> "TIMESTAMP(6) WITH TIME ZONE";
                    case REF -> "UUID";
                    default -> throw new VclException("NATIVE_TYPE_UNSUPPORTED", "");
                };
                columns.add(new Column(id, name, sql, type.nullable()));
            }
        }
    }
}
