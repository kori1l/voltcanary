package com.voltcanary.device.configurable.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.voltcanary.device.configurable.domain.NativeDataset;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclType;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Bounded v1 SQL projection. Unsupported scalar pushdown never falls back to a scan/local evaluator. */
final class NativeQuerySql {
    private final NativeDataset dataset;
    private final List<Object> parameters = new ArrayList<>();
    NativeQuerySql(NativeDataset dataset) { this.dataset = dataset; }
    List<Object> parameters() { return parameters; }

    String predicate(JsonNode expression) {
        String op = expression.path("op").asText();
        if (op.equals("CONST")) {
            var literal = expression.path("literal");
            if (literal.path("type").asText().equals("BOOLEAN")) return literal.path("value").asBoolean() ? "TRUE" : "FALSE";
            if (literal.path("type").asText().equals("NULL")) return "NULL";
            throw unsupported();
        }
        if (op.equals("FIELD")) {
            UUID field = UUID.fromString(expression.path("fieldId").asText());
            if (dataset.schema().type(field).kind() != VclType.Kind.BOOLEAN) throw unsupported();
            return field(field);
        }
        String method = expression.path("method").asText();
        var args = expression.path("args");
        return switch (method) {
            case "and", "or" -> "(" + predicate(args.get(0)) + (method.equals("and") ? " AND " : " OR ") + predicate(args.get(1)) + ")";
            case "not" -> "(NOT " + predicate(args.get(0)) + ")";
            case "is_known" -> {
                if (!List.of("CONST", "FIELD").contains(args.get(0).path("op").asText())) throw unsupported();
                yield "TRUE"; // Only known native values/null, never an unevaluated call that could fail.
            }
            case "is_null" -> "(" + value(args.get(0)) + " IS NULL)";
            case "eq", "ne", "lt", "le", "gt", "ge" -> {
                if (!method.equals("eq") && !method.equals("ne") && (equalityOnly(args.get(0)) || equalityOnly(args.get(1)))) {
                    throw unsupported();
                }
                String sql = switch (method) {
                    case "eq" -> "=";
                    case "ne" -> "<>";
                    case "lt" -> "<";
                    case "le" -> "<=";
                    case "gt" -> ">";
                    default -> ">=";
                };
                yield "(" + value(args.get(0)) + sql + value(args.get(1)) + ")";
            }
            default -> throw unsupported();
        };
    }

    private String value(JsonNode expression) {
        if (expression.path("op").asText().equals("FIELD")) return field(UUID.fromString(expression.path("fieldId").asText()));
        if (!expression.path("op").asText().equals("CONST")) throw unsupported();
        var literal = expression.path("literal");
        String kind = literal.path("type").asText();
        if (kind.equals("NULL")) return "NULL";
        Object value = switch (kind) {
            case "BOOLEAN" -> literal.path("value").booleanValue();
            case "INT64", "DURATION" -> Long.parseLong(literal.path("value").asText());
            case "DECIMAL" -> new java.math.BigDecimal(literal.path("value").asText());
            case "DATE" -> java.time.LocalDate.parse(literal.path("value").asText());
            case "INSTANT" -> java.time.OffsetDateTime.parse(literal.path("value").asText());
            case "TEXT", "ENUM" -> {
                String text = literal.path("value").asText();
                if (text.indexOf(0) >= 0) throw new VclException("NATIVE_TEXT_UNSUPPORTED", "");
                yield text;
            }
            default -> throw unsupported();
        };
        parameters.add(value);
        return "?";
    }

    private String field(UUID id) {
        if (id.equals(dataset.recordIdFieldId())) return "record_id";
        var type = dataset.schema().type(id);
        if (List.of(VclType.Kind.POINT, VclType.Kind.INTERVAL).contains(type.kind())) throw unsupported();
        if (!dataset.schema().indexes().contains(id)) throw new VclException("INDEX_REQUIRED", "");
        return NativeSqlLayout.identifier(NativeSqlLayout.column(id));
    }

    private boolean equalityOnly(JsonNode operand) {
        if (operand.path("op").asText().equals("FIELD")) {
            UUID id = UUID.fromString(operand.path("fieldId").asText());
            return id.equals(dataset.recordIdFieldId())
                    || List.of(VclType.Kind.TEXT, VclType.Kind.ENUM, VclType.Kind.REF).contains(dataset.schema().type(id).kind());
        }
        return List.of("TEXT", "ENUM", "REF").contains(operand.at("/literal/type").asText());
    }

    private static VclException unsupported() { return new VclException("QUERY_PUSHDOWN_UNSUPPORTED", ""); }
}
