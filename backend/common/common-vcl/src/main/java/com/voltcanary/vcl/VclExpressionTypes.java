package com.voltcanary.vcl;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;

final class VclExpressionTypes {
    private final VclRegistry registry;
    private final VclUnits units;
    private final java.util.function.Function<java.util.UUID, VclType> references;
    private int count;
    private final Map<JsonNode, VclType> inferred = new java.util.IdentityHashMap<>();

    VclExpressionTypes(VclRegistry registry, VclUnits units, java.util.function.Function<java.util.UUID, VclType> references) {
        this.registry = registry;
        this.units = units;
        this.references = references;
    }

    VclType predicate(JsonNode expression, Map<String, VclType> fields, String path) {
        var result = infer(expression, fields, path, 1);
        VclException.require(result.kind() == VclType.Kind.BOOLEAN, "PREDICATE_TYPE", path);
        return result;
    }

    VclType infer(JsonNode expression, Map<String, VclType> fields, String path, int depth) {
        VclException.require(depth <= 16 && ++count <= 128, "EXPRESSION_LIMIT", path);
        var result = inferType(expression, fields, path, depth);
        inferred.put(expression, result);
        return result;
    }

    VclType inferredType(JsonNode expression) {
        return java.util.Objects.requireNonNull(inferred.get(expression), "Expression was not checked");
    }

    private VclType inferType(JsonNode expression, Map<String, VclType> fields, String path, int depth) {
        String operation = expression.path("op").asText();
        if (operation.equals("FIELD")) return field(fields, expression.path("fieldId").asText(), path);
        if (operation.equals("CONST")) {
            var literal = expression.path("literal");
            VclValues.normalized(literal);
            var result = VclType.from(literal);
            if (result.kind() == VclType.Kind.REF) result = references.apply(java.util.UUID.fromString(literal.path("value").asText()));
            if (result.numeric()) units.require(result.unit(), path);
            return result;
        }
        String method = expression.path("method").asText();
        var contract = registry.scalars().get(method);
        VclException.require(contract != null, "METHOD_UNKNOWN", path);
        var args = expression.path("args");
        VclException.require(args.size() == contract.arity(), "METHOD_ARITY", path);
        var types = new ArrayList<VclType>();
        for (int i = 0; i < args.size(); i++) types.add(infer(args.get(i), fields, path + "/args/" + i, depth + 1));
        VclType a = types.getFirst();
        VclType b = types.size() > 1 ? types.get(1) : null;
        switch (method) {
            case "is_known", "is_null" -> { return VclType.BOOLEAN; }
            case "and", "or", "not" -> {
                for (var type : types) require(type, VclType.Kind.BOOLEAN, path);
                return VclType.BOOLEAN;
            }
            case "eq", "ne", "lt", "le", "gt", "ge" -> {
                a.compatible(b, path);
                if (!Set.of("eq", "ne").contains(method)) {
                    VclException.require(Set.of(VclType.Kind.INT64, VclType.Kind.DECIMAL, VclType.Kind.TEXT,
                            VclType.Kind.DATE, VclType.Kind.INSTANT, VclType.Kind.DURATION, VclType.Kind.NULL).contains(a.kind()),
                            "TYPE_NOT_ORDERED", path);
                }
                return VclType.BOOLEAN;
            }
            case "text_eq", "text_prefix" -> {
                require(a, VclType.Kind.TEXT, path);
                require(b, VclType.Kind.TEXT, path);
                return VclType.BOOLEAN;
            }
            case "distance_m" -> {
                require(a, VclType.Kind.POINT, path);
                require(b, VclType.Kind.POINT, path);
                return new VclType(VclType.Kind.DECIMAL, a.nullable() || b.nullable(), "m", null);
            }
            case "inside" -> {
                require(a, VclType.Kind.POINT, path);
                require(b, VclType.Kind.REF, path);
                VclException.require("RESOURCE:SPATIAL_FEATURE".equals(b.referenceTarget()), "REF_TYPE_MISMATCH", path);
                require(types.get(2), VclType.Kind.DECIMAL, path);
                VclException.require(types.get(2).unit().equals("m"), "UNIT_MISMATCH", path);
                return VclType.BOOLEAN;
            }
            case "convert_unit", "duration_from" -> {
                VclException.require(a.numeric(), "TYPE_MISMATCH", path);
                VclException.require(args.get(1).path("op").asText().equals("CONST") && b.kind() == VclType.Kind.TEXT,
                        "CONSTANT_UNIT_REQUIRED", path);
                String target = args.get(1).at("/literal/value").asText();
                if (method.equals("duration_from")) {
                    require(a, VclType.Kind.DECIMAL, path);
                    VclException.require(a.unit().equals("1") && Set.of("us", "ms", "s", "min", "h").contains(target),
                            "UNIT_MISMATCH", path);
                    return new VclType(VclType.Kind.DURATION, a.nullable(), "1", null);
                }
                units.compatible(a.unit(), target, path);
                return new VclType(VclType.Kind.DECIMAL, a.nullable(), target, null);
            }
            case "add", "subtract", "multiply", "divide" -> {
                VclException.require(a.numeric() && b.numeric(), "TYPE_MISMATCH", path);
                VclException.require(!units.require(a.unit(), path).absolute() && !units.require(b.unit(), path).absolute(),
                        "UNIT_ARITHMETIC_UNSUPPORTED", path);
                if (Set.of("add", "subtract").contains(method)) {
                    a.compatible(b, path);
                    return new VclType(a.kind(), a.nullable() || b.nullable(), a.unit(), null);
                }
                require(a, VclType.Kind.DECIMAL, path);
                require(b, VclType.Kind.DECIMAL, path);
                String unit;
                if (b.unit().equals("1")) unit = a.unit();
                else if (method.equals("multiply") && a.unit().equals("1")) unit = b.unit();
                else if (method.equals("divide") && a.unit().equals(b.unit())) unit = "1";
                else throw new VclException("UNIT_RESULT_UNREGISTERED", path);
                return new VclType(VclType.Kind.DECIMAL, a.nullable() || b.nullable(), unit, null);
            }
            default -> throw new VclException("METHOD_UNKNOWN", path);
        }
    }

    static VclType field(Map<String, VclType> fields, String id, String path) {
        var result = fields.get(id.toLowerCase());
        VclException.require(result != null, "FIELD_NOT_IN_SCOPE", path);
        return result;
    }

    static void require(VclType type, VclType.Kind kind, String path) {
        VclException.require(type.kind() == kind, "TYPE_MISMATCH", path);
    }
}
