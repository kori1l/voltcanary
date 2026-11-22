package com.voltcanary.aiagent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Exact accepted server output, projected for target selection without inferring meaning from labels. */
public final class CurrentWidgetInventory {

    private CurrentWidgetInventory() {}

    public static JsonNode from(ObjectMapper mapper, JsonNode parameters) {
        var inventory = mapper.createObjectNode().put("version", "widget-inventory-v1");
        var widgets = inventory.putArray("widgets");
        JsonNode definition = parameters.path("definition");
        JsonNode intent = parameters.path("prepareIntent");
        for (var widget : definition.path("widgets")) {
            String key = widget.path("id").asText();
            var entry = widgets
                .addObject()
                .put("key", key)
                .put("type", widget.path("type").asText());
            entry.set("title", widget.path("title").deepCopy());
            entry.set("binding", widget.path("binding").deepCopy());
            entry.set("query", widget.path("query").deepCopy());
            entry.set("presentation", widget.path("presentation").deepCopy());
            entry.set("settings", widget.path("settings").deepCopy());
            entry.put(
                "role",
                switch (widget.path("type").asText()) {
                    case "vehicle-value", "latest-value", "metric-summary" -> "CURRENT";
                    case "vehicle-timeline", "time-series" -> "HISTORY";
                    case "vehicle-map" -> "MAP";
                    default -> "OTHER";
                }
            );
            // Preserve the effective field beside the role after the wire projection removes the full query.
            var fieldKey = widget.at("/presentation/vehicleField");
            if (!fieldKey.isTextual()) fieldKey = widget.at("/query/metric");
            if (fieldKey.isTextual()) entry.put("fieldKey", fieldKey.textValue());
            for (var panel : intent.path("widgets"))
                if (panel.path("key").asText().equals(key)) {
                    for (String field : List.of(
                        "metric",
                        "vehicleField",
                        "dataRef",
                        "sourceId",
                        "deviceIds",
                        "width",
                        "height",
                        "period"
                    ))
                        if (panel.has(field)) entry.set(field, panel.get(field).deepCopy());
                }
            var sizes = entry.putObject("breakpoints");
            definition
                .path("layouts")
                .fields()
                .forEachRemaining(breakpoint -> {
                    var placements = ordered(breakpoint.getValue());
                    for (int index = 0; index < placements.size(); index++) {
                        var placement = placements.get(index);
                        if (!placement.path("widgetId").asText().equals(key)) continue;
                        var size = sizes.putObject(breakpoint.getKey()).put("order", index);
                        for (String field : List.of("x", "y", "w", "h"))
                            size.set(field, placement.path(field).deepCopy());
                    }
                });
        }
        var orders = inventory.putObject("order");
        definition
            .path("layouts")
            .fields()
            .forEachRemaining(breakpoint -> {
                var order = orders.putArray(breakpoint.getKey());
                ordered(breakpoint.getValue()).forEach(placement ->
                    order.add(placement.path("widgetId").asText())
                );
            });
        return inventory;
    }

    private static List<JsonNode> ordered(JsonNode layout) {
        var values = new ArrayList<JsonNode>();
        layout.forEach(values::add);
        values.sort(
            Comparator.comparingInt((JsonNode node) -> node.path("y").asInt()).thenComparingInt(
                node -> node.path("x").asInt()
            )
        );
        return values;
    }
}
