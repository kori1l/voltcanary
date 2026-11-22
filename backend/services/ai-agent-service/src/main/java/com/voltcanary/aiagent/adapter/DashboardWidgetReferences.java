package com.voltcanary.aiagent.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Short references are scoped to the accepted widget inventory for this request. */
final class DashboardWidgetReferences {

    private final Map<String, String> aliases = new LinkedHashMap<>();

    DashboardWidgetReferences(JsonNode request) {
        for (var load : request.path("loadResults")) {
            if (!"review.base".equals(load.path("loaderId").asText())) continue;
            if (
                !request.at("/context/baseReviewId").isTextual() ||
                !request.at("/context/baseReviewId").equals(load.at("/data/reviewId"))
            ) continue;
            for (var action : load.at("/data/actions")) {
                var widgets = action.at("/currentWidgetInventory/widgets");
                if (!widgets.isArray() || widgets.isEmpty()) continue;
                var keys = new java.util.LinkedHashSet<String>();
                for (var widget : widgets) {
                    if (widget.path("key").isTextual()) keys.add(widget.path("key").asText());
                }
                int index = 0;
                for (String key : keys) {
                    String alias;
                    do {
                        alias = "widget:" + ++index;
                    } while (keys.contains(alias));
                    aliases.put(alias, key);
                }
                return;
            }
        }
    }

    boolean available() {
        return !aliases.isEmpty();
    }

    void describe(ObjectNode request) {
        var refs = request.withObject("/changeRequest").putObject("widgetRefs");
        aliases.forEach(refs::put);
    }

    void project(JsonNode schema) {
        for (var operation : schema.at(
            "/$defs/command/properties/payload/properties/operations/items/anyOf"
        )) {
            for (String field : List.of("targets", "anchor", "widgetKey", "target")) {
                addAliases(operation.path("properties").path(field));
            }
        }
    }

    private void addAliases(JsonNode schema) {
        if (schema.path("enum") instanceof ArrayNode values) {
            for (var entry : aliases.entrySet()) {
                if (
                    java.util.stream.StreamSupport.stream(values.spliterator(), false).anyMatch(
                        value -> entry.getValue().equals(value.asText())
                    )
                ) values.add(entry.getKey());
            }
        }
        addNestedAliases(schema);
    }

    private void addNestedAliases(JsonNode schema) {
        for (String branch : List.of("items", "anyOf", "oneOf")) {
            var child = schema.path(branch);
            if (child.isArray()) child.forEach(this::addAliases);
            else if (child.isObject()) addAliases(child);
        }
    }

    JsonNode lower(JsonNode proposal) {
        var result = proposal.deepCopy();
        for (var operation : result.at("/command/payload/operations")) {
            if (!(operation instanceof ObjectNode object)) continue;
            for (String field : List.of("anchor", "widgetKey", "target")) {
                String key = aliases.get(object.path(field).asText());
                if (key != null) object.put(field, key);
            }
            if (object.path("targets") instanceof ArrayNode targets) {
                for (int i = 0; i < targets.size(); i++) {
                    String key = aliases.get(targets.get(i).asText());
                    if (key != null) targets.set(i, object.textNode(key));
                }
            }
        }
        return result;
    }
}
