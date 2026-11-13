package com.voltcanary.vcl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.Map;

/** Method/config capability projections are derived from the same closed grammar used by the parser. */
public final class VclRegistry {
    public record Scalar(String name, int arity, String signature) { }
    public record Operator(String name, java.util.Set<String> ports, JsonNode configSchema) {
        public Operator {
            ports = java.util.Set.copyOf(ports);
            configSchema = configSchema.deepCopy();
        }
        @Override public JsonNode configSchema() { return configSchema.deepCopy(); }
    }
    private final Map<String, Scalar> scalars;
    private final Map<String, Operator> operators;
    private final JsonNode schema;

    public VclRegistry(VclParser parser) {
        schema = parser.grammar();
        var scalarMap = new LinkedHashMap<String, Scalar>();
        findCalls(schema.path("$defs").path("Expression"), scalarMap);
        scalars = Map.copyOf(scalarMap);
        var operatorMap = new LinkedHashMap<String, Operator>();
        for (var variant : schema.at("/$defs/Rule/properties/nodes/items/oneOf")) {
            var properties = variant.path("properties");
            String name = properties.at("/method/const").asText();
            var ports = new java.util.LinkedHashSet<String>();
            properties.at("/inputs/properties").fieldNames().forEachRemaining(ports::add);
            operatorMap.put(name, new Operator(name, ports, properties.path("config")));
        }
        operators = Map.copyOf(operatorMap);
        if (scalars.size() != 21 || operators.size() != 12) throw new IllegalStateException("VCL registry projection incomplete");
    }

    private static void findCalls(JsonNode node, Map<String, Scalar> result) {
        if (node.at("/properties/op/const").asText().equals("CALL")) {
            for (var clause : node.path("allOf")) {
                var condition = clause.at("/if/properties/method");
                var methods = condition.path("enum");
                int arity = clause.at("/then/properties/args/minItems").asInt(-1);
                if (condition.has("const")) {
                    String name = condition.path("const").asText();
                    result.put(name, new Scalar(name, arity, signature(name)));
                }
                for (var method : methods) result.put(method.asText(), new Scalar(method.asText(), arity, signature(method.asText())));
            }
        }
        node.forEach(child -> findCalls(child, result));
    }

    public Map<String, Scalar> scalars() { return scalars; }
    public Map<String, Operator> operators() { return operators; }
    public JsonNode schema() { return schema.deepCopy(); }
    public String digest() {
        var contract = JsonNodeFactory.instance.objectNode();
        contract.set("grammar", schema);
        contract.set("scalarKernels", VclScalarContracts.descriptor());
        return VclCanonical.sha256(VclCanonical.encode(contract));
    }

    public ObjectNode capabilities() {
        var result = JsonNodeFactory.instance.objectNode().put("language", "VCL/1").put("registryVersion", "vcl-1-spec-2");
        result.put("registryDigest", digest());
        result.set("scalarKernelContract", VclScalarContracts.descriptor());
        var scalarList = result.putArray("scalars");
        scalars.values().stream().sorted(java.util.Comparator.comparing(Scalar::name)).forEach(s ->
                scalarList.addObject().put("method", s.name()).put("arity", s.arity()).put("signature", s.signature()));
        var graph = result.putArray("operators");
        operators.values().stream().sorted(java.util.Comparator.comparing(Operator::name)).forEach(o -> {
            var entry = graph.addObject().put("method", o.name());
            var ports = entry.putArray("ports");
            o.ports().stream().sorted().forEach(ports::add);
            entry.set("configSchema", o.configSchema());
        });
        result.set("$defs", schema.path("$defs").deepCopy());
        return result;
    }

    private static String signature(String method) {
        return switch (method) {
            case "eq", "ne" -> "(T,T)->Truth";
            case "lt", "le", "gt", "ge" -> "(Ordered<T>,T)->Truth";
            case "and", "or" -> "(Truth,Truth)->Truth";
            case "not" -> "(Truth)->Truth";
            case "is_known", "is_null" -> "(T?)->Truth";
            case "add", "subtract" -> "(Numeric<T>,T)->T";
            case "multiply", "divide" -> "(DECIMAL,DECIMAL)->DECIMAL";
            case "convert_unit" -> "(Numeric,CONST<TEXT:UnitId>)->DECIMAL";
            case "duration_from" -> "(DECIMAL<1>,CONST<TEXT:DurationUnit>)->DURATION";
            case "text_eq", "text_prefix" -> "(TEXT,TEXT)->Truth";
            case "distance_m" -> "(POINT,POINT)->DECIMAL<m>";
            case "inside" -> "(POINT,REF<SPATIAL_FEATURE>,DECIMAL<m>)->Truth";
            default -> throw new IllegalStateException("Unregistered scalar signature");
        };
    }
}
