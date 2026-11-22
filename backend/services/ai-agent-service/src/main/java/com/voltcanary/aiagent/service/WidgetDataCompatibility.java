package com.voltcanary.aiagent.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** The same owner view relation serves model schemas, diagnostics and final preparation. */
final class WidgetDataCompatibility {

    private WidgetDataCompatibility() {}

    static boolean supports(Map<?, ?> choice, Map<?, ?> widget) {
        return (
            choice.get("views") instanceof List<?> views &&
            views
                .stream()
                .anyMatch(
                    item ->
                        item instanceof Map<?, ?> view &&
                        Objects.equals(widget.get("type"), view.get("widgetType")) &&
                        (widget.get("visualization") == null ||
                            (view.get("visualizations") instanceof List<?> modes &&
                                modes.contains(widget.get("visualization"))))
                )
        );
    }

    static List<String> choices(Map<String, Object> context, Map<?, ?> widget) {
        if (!(context.get("dashboardDataChoices") instanceof List<?> values)) return List.of();
        return values
            .stream()
            .filter(Map.class::isInstance)
            .map(Map.class::cast)
            .filter(choice -> supports(choice, widget))
            .map(choice -> choice.get("dataRef"))
            .filter(String.class::isInstance)
            .map(String.class::cast)
            .distinct()
            .toList();
    }

    static List<String> typeChoices(Map<String, Object> context, Map<?, ?> widget) {
        return choices(context, Map.of("type", String.valueOf(widget.get("type"))));
    }

    static List<Map<?, ?>> widgets(Map<String, Object> context) {
        if (
            !(context.get("previousDashboardIntent") instanceof Map<?, ?> base) ||
            !(base.get("widgets") instanceof List<?> values)
        ) return List.of();
        return values
            .stream()
            .filter(Map.class::isInstance)
            .<Map<?, ?>>map(item -> (Map<?, ?>) item)
            .toList();
    }

    static void validateReplacements(JsonNode payload, Map<String, Object> context) {
        if (!(context.get("dashboardDataChoices") instanceof List<?>)) return;
        var current = new LinkedHashMap<String, Map<Object, Object>>();
        for (var widget : widgets(context))
            current.put(String.valueOf(widget.get("key")), new LinkedHashMap<>(widget));
        int index = -1;
        for (var operation : payload.path("operations")) {
            index++;
            if (
                operation.path("op").asText().equals("show_history") &&
                operation.path("view").isTextual()
            ) {
                var widget = current.get(operation.path("widgetKey").asText());
                if (widget != null) widget.put("visualization", operation.path("view").asText());
            }
            if (
                !operation.path("op").asText().equals("set_data") ||
                !operation.path("dataRef").isTextual()
            ) continue;
            for (var widget : current.values()) {
                if (
                    !Objects.equals(widget.get("key"), operation.path("widgetKey").asText())
                ) continue;
                var choices = choices(context, widget);
                if (
                    !choices.contains(operation.path("dataRef").asText())
                ) throw new IllegalArgumentException(
                    "INCOMPATIBLE_DATA_REF /operations/" +
                        index +
                        "/dataRef; widgetKey=" +
                        widget.get("key") +
                        "; widgetType=" +
                        widget.get("type") +
                        "; visualization=" +
                        widget.get("visualization") +
                        "; allowedDataRefs=" +
                        choices.stream().limit(12).toList()
                );
            }
        }
    }
}
