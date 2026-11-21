package com.voltcanary.device.configurable.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.voltcanary.device.configurable.domain.NativeDataset;
import com.voltcanary.device.configurable.domain.NativeRow;
import com.voltcanary.device.configurable.domain.NativeSchema;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclException;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

/** Exact typed wire/SQL boundary after common-vcl validation. No numeric double coercions. */
final class NativeRowCodec {
    private NativeRowCodec() { }

    static List<String> columns(NativeDataset dataset, UUID id) {
        if (dataset.recordIdFieldId().equals(id)) return List.of("record_id");
        return new NativeSqlLayout(dataset).columns().stream().filter(column -> column.fieldId().equals(id))
                .map(NativeSqlLayout.Column::name).toList();
    }

    static List<Object> encode(NativeSchema schema, UUID id, JsonNode literal) {
        var kind = schema.type(id).kind();
        var values = new ArrayList<Object>();
        if (literal.path("type").asText().equals("NULL")) {
            values.add(null);
            if (kind == com.voltcanary.vcl.VclType.Kind.POINT || kind == com.voltcanary.vcl.VclType.Kind.INTERVAL) values.add(null);
            return values;
        }
        var value = literal.path("value");
        switch (kind) {
            case BOOLEAN -> values.add(value.booleanValue());
            case INT64, DURATION -> values.add(Long.parseLong(value.asText()));
            case DECIMAL -> values.add(new BigDecimal(value.asText()));
            case TEXT, ENUM -> {
                if (value.asText().indexOf('\0') >= 0) throw new VclException("NATIVE_TEXT_UNSUPPORTED", "");
                values.add(value.asText());
            }
            case DATE -> values.add(LocalDate.parse(value.asText()));
            case INSTANT -> values.add(OffsetDateTime.parse(value.asText()));
            case REF -> values.add(UUID.fromString(value.asText()));
            case POINT -> {
                values.add(value.path("longitude").doubleValue());
                values.add(value.path("latitude").doubleValue());
            }
            case INTERVAL -> {
                values.add(OffsetDateTime.parse(value.path("start").asText()));
                values.add(value.path("end").isNull() ? null : OffsetDateTime.parse(value.path("end").asText()));
            }
            default -> throw new VclException("NATIVE_TYPE_UNSUPPORTED", "");
        }
        return values;
    }

    static NativeRow read(ResultSet row, NativeDataset dataset, List<UUID> fields) throws SQLException {
        var values = new LinkedHashMap<UUID, JsonNode>();
        UUID record = row.getObject("record_id", UUID.class);
        for (UUID id : fields) {
            if (id.equals(dataset.recordIdFieldId())) {
                values.put(id, JsonNodeFactory.instance.objectNode().put("type", "REF").put("value", record.toString()));
            } else {
                values.put(id, literal(row, dataset.schema(), id));
            }
        }
        return new NativeRow(record, Long.toString(row.getLong("record_version")), values);
    }

    private static JsonNode literal(ResultSet row, NativeSchema schema, UUID id) throws SQLException {
        var type = schema.type(id);
        String name = NativeSqlLayout.column(id);
        String first = switch (type.kind()) {
            case POINT -> name + "_lon";
            case INTERVAL -> name + "_start";
            default -> name;
        };
        if (row.getObject(first) == null) return JsonNodeFactory.instance.objectNode().put("type", "NULL").putNull("value");
        var result = JsonNodeFactory.instance.objectNode().put("type", type.kind().name());
        if (type.numeric() && !type.unit().equals("1")) result.put("unit", type.unit());
        switch (type.kind()) {
            case BOOLEAN -> result.put("value", row.getBoolean(name));
            case INT64, DURATION -> result.put("value", Long.toString(row.getLong(name)));
            case DECIMAL -> result.put("value", row.getBigDecimal(name).stripTrailingZeros().toPlainString());
            case TEXT, ENUM -> result.put("value", row.getString(name));
            case REF -> result.put("value", row.getObject(name, UUID.class).toString());
            case DATE -> result.put("value", row.getObject(name, LocalDate.class).toString());
            case INSTANT -> result.put("value", row.getObject(name, OffsetDateTime.class).toInstant().toString());
            case POINT -> result.putObject("value").put("longitude", row.getDouble(name + "_lon")).put("latitude", row.getDouble(name + "_lat"));
            case INTERVAL -> {
                var value = result.putObject("value").put("start", row.getObject(name + "_start", OffsetDateTime.class).toInstant().toString());
                var end = row.getObject(name + "_end", OffsetDateTime.class);
                if (end == null) value.putNull("end"); else value.put("end", end.toInstant().toString());
            }
            default -> throw new VclException("NATIVE_TYPE_UNSUPPORTED", "");
        }
        return result;
    }

    static String keyTuple(NativeSchema schema, java.util.Map<UUID, JsonNode> values) {
        var tuple = JsonNodeFactory.instance.arrayNode().add("NATIVE_KEY/1");
        for (UUID key : schema.keys()) {
            var value = values.get(key);
            if (value == null || value.path("type").asText().equals("NULL")) throw new VclException("UNIQUE_TARGET_KEY_REQUIRED", "");
            tuple.addObject().put("fieldId", key.toString()).put("type", schema.type(key).kind().name()).set("value", value.path("value").deepCopy());
        }
        return VclCanonical.encode(tuple);
    }
}
