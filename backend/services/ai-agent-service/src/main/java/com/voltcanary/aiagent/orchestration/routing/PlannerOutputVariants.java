package com.voltcanary.aiagent.orchestration.routing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/** Compiles finite planner conditionals into disjoint objects, without weakening their field types. */
final class PlannerOutputVariants {
    private PlannerOutputVariants() { }

    static JsonNode expand(JsonNode schema, ObjectMapper mapper) {
        var decisions = schema.at("/properties/decision/enum");
        if (!schema.path("allOf").isArray() || !decisions.isArray()) return schema;
        var result = mapper.createObjectNode();
        var variants = result.putArray("oneOf");
        for (var decision : decisions) {
            var branch = schema.<ObjectNode>deepCopy();
            branch.remove("allOf");
            var discriminator = (ObjectNode) branch.at("/properties/decision");
            discriminator.remove("enum");
            discriminator.set("const", decision.deepCopy());
            for (var rule : schema.path("allOf")) {
                var conditions = rule.at("/if/properties");
                var condition = conditions.path("decision");
                if (conditions.size() != 1 || condition.isMissingNode() || rule.has("else")
                        || !rule.at("/then/properties").isObject())
                    throw new ModelRouting.Stop("STRICT_PLANNER_CONDITIONAL_UNSUPPORTED");
                boolean applies = decision.equals(condition.path("const"));
                for (var value : condition.path("enum")) applies |= decision.equals(value);
                if (!applies) continue;
                rule.at("/then/properties").fields().forEachRemaining(field -> {
                    var properties = (ObjectNode) branch.path("properties");
                    var narrowed = properties.path(field.getKey()).<ObjectNode>deepCopy();
                    if (field.getValue().has("$ref")) narrowed = mapper.createObjectNode();
                    else if (field.getValue().has("type")) narrowed.remove(List.of("anyOf", "oneOf", "$ref"));
                    narrowed.setAll((ObjectNode) field.getValue());
                    properties.set(field.getKey(), narrowed);
                });
            }
            variants.add(branch);
        }
        return result;
    }
}
