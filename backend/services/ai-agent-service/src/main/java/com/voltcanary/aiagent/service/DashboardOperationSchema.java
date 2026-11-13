package com.voltcanary.aiagent.service;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.voltcanary.aiagent.domain.DashboardOperation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Generated from the sealed wire records; the same schema is sent to strict-output providers. */
public final class DashboardOperationSchema {
    private DashboardOperationSchema() { }
    private static Map<String, Object> string() { return Map.of("type", "string"); }
    private static Map<String, Object> enumeration(String... values) { return Map.of("type", "string", "enum", List.of(values)); }
    private static Map<String, Object> integer(int minimum, int maximum) { return Map.of("type", "integer", "minimum", minimum, "maximum", maximum); }
    private static Map<String, Object> nullable(Map<String, Object> schema) { return Map.of("anyOf", List.of(schema, Map.of("type", "null"))); }
    private static Map<String, Object> array(Map<String, Object> items, int maximum) {
        return Map.of("type", "array", "items", items, "maxItems", maximum);
    }
    private static Map<String, Object> object(Map<String, Object> properties) {
        return Map.of("type", "object", "properties", properties, "required", new ArrayList<>(properties.keySet()), "additionalProperties", false);
    }
    public static Map<String, Object> envelope() {
        return envelope(null, null);
    }
    private static Map<String, Object> envelope(List<String> existingKeys, Map<String, List<String>> dataRefs) {
        var variants = new ArrayList<Map<String, Object>>();
        for (var subtype : DashboardOperation.class.getAnnotation(JsonSubTypes.class).value()) {
            List<String> offered = dataRefs == null ? null : dataRefs.get(subtype.name());
            if (existingKeys != null && existingKeys.isEmpty()
                    && Set.of("keep_review", "set_data", "restore_review", "remove_widgets").contains(subtype.name())) continue;
            if (offered != null && offered.isEmpty()
                    && Set.of("show_map", "show_current", "show_history", "set_data").contains(subtype.name())) continue;
            var fields = new LinkedHashMap<String, Object>();
            fields.put("op", Map.of("type", "string", "enum", List.of(subtype.name())));
            for (var field : subtype.value().getRecordComponents()) {
                Map<String, Object> schema;
                if (field.getName().equals("settings")) {
                    var value = Map.<String, Object>of("anyOf", List.of(string(), Map.of("type", "boolean"),
                            Map.of("type", "number"), array(string(), 50), Map.of("type", "null")));
                    schema = array(object(Map.of("key", string(), "value", value)), 32);
                } else if (field.getType().equals(List.class)) {
                    var bounded = new LinkedHashMap<>(array(string(), 12));
                    if (!(subtype.value().equals(DashboardOperation.Period.class) && field.getName().equals("targets")))
                        bounded.put("minItems", 1);
                    schema = bounded;
                }
                else if (field.getType().equals(Integer.class)) schema = Map.of("type", "integer");
                else schema = string();
                schema = switch (field.getName()) {
                    case "id" -> Map.of("type", "string", "pattern", "^[A-Za-z][A-Za-z0-9_-]{0,39}$");
                    case "position" -> enumeration("TOP", "BOTTOM", "BEFORE", "AFTER");
                    case "width" -> enumeration("FULL", "HALF", "THIRD");
                    case "view" -> enumeration("line", "list", "area", "bar", "horizontal-bar");
                    case "period" -> enumeration("LAST_1_HOUR", "LAST_6_HOURS", "LAST_24_HOURS", "LAST_7_DAYS", "LAST_30_DAYS");
                    case "height" -> integer(1, 12);
                    case "heightPercent" -> integer(1, 400);
                    case "restoreSteps" -> integer(1, 30);
                    case "title" -> Map.of("type", "string", "minLength", 1, "maxLength", 120);
                    case "name" -> Map.of("type", "string", "minLength", 1, "maxLength", 80);
                    case "text" -> Map.of("type", "string", "maxLength", 1000);
                    case "dataRef" -> offered == null ? string() : Map.of("type", "string", "enum", offered);
                    default -> schema;
                };
                if (Set.of("widgetKey", "view", "anchor", "width", "height", "heightPercent").contains(field.getName())
                        || field.getName().equals("period") && subtype.value().equals(DashboardOperation.ShowHistory.class)
                        || field.getName().equals("targets") && subtype.value().equals(DashboardOperation.Period.class)) schema = nullable(schema);
                if (field.getName().equals("widgetKey") && existingKeys != null) {
                    if (subtype.value().equals(DashboardOperation.RenameWidget.class)) {
                        var local = Map.<String, Object>of("type", "string", "pattern", "^@[A-Za-z][A-Za-z0-9_-]{0,39}$");
                        schema = existingKeys.isEmpty() ? local : Map.of("anyOf", List.of(Map.of("type", "string", "enum", existingKeys), local));
                    } else schema = existingKeys.isEmpty() ? Map.of("type", "null")
                            : nullable(Map.of("type", "string", "enum", existingKeys));
                }
                fields.put(field.getName(), schema);
            }
            if (subtype.name().equals("place_widgets")) {
                var edge = new LinkedHashMap<>(fields);
                edge.put("position", enumeration("TOP", "BOTTOM"));
                edge.put("anchor", Map.of("type", "null"));
                variants.add(object(edge));
                var relative = new LinkedHashMap<>(fields);
                relative.put("position", enumeration("BEFORE", "AFTER"));
                relative.put("anchor", Map.of("type", "string", "minLength", 1));
                variants.add(object(relative));
            } else variants.add(object(fields));
        }
        return object(Map.of("protocol", Map.of("type", "string", "enum", List.of(DashboardOperationProtocol.VERSION)),
                "operations", Map.of("type", "array", "items", Map.of("anyOf", variants), "minItems", 1, "maxItems", 24)));
    }
    public static Map<String, Object> response() {
        return response(Map.of());
    }
    /** V2 command payload only; legacy model-plan wrappers are not domain commands. */
    public static Map<String, Object> envelope(Map<String, Object> context) {
        List<String> keys = context.get("previousDashboardIntent") instanceof Map<?, ?> previous && previous.get("widgets") instanceof List<?> widgets
                ? widgets.stream().filter(Map.class::isInstance).map(item -> String.valueOf(((Map<?, ?>) item).get("key"))).toList() : List.of();
        var envelope = envelope(keys, dataRefs(context));
        return context.get("dashboardDataChoices") instanceof List<?>
                ? DashboardOperationConstraints.replacements(envelope, context) : envelope;
    }
    public static Map<String, Object> response(Map<String, Object> context) {
        List<String> keys = context.get("previousDashboardIntent") instanceof Map<?, ?> previous && previous.get("widgets") instanceof List<?> widgets
                ? widgets.stream().filter(Map.class::isInstance).map(item -> String.valueOf(((Map<?, ?>) item).get("key"))).toList() : List.of();
        var suggestions = new LinkedHashMap<String, Object>();
        for (String field : List.of("name", "deviceRefs", "sourceRef", "sourceId", "period")) suggestions.put(field, nullable(string()));
        var clarification = object(Map.of("missingFields", array(string(), 12), "suggestedReply", string(),
                "suggestionLabel", string(), "suggestedValues", object(suggestions)));
        var action = object(Map.of("tool", Map.of("type", "string", "enum", List.of("CREATE_DASHBOARD")),
                "title", string(), "description", string(), "parameters", envelope(context)));
        return object(Map.of("summary", string(), "explanation", string(), "clarification", nullable(clarification), "actions", array(action, 1)));
    }

    private static Map<String, List<String>> dataRefs(Map<String, Object> context) {
        if (!(context.get("dashboardDataChoices") instanceof List<?> choices)) return null;
        var result = new LinkedHashMap<String, List<String>>();
        for (String operation : List.of("show_map", "show_current", "show_history", "set_data")) {
            Set<String> types = switch (operation) {
                case "show_map" -> Set.of("vehicle-map");
                case "show_current" -> Set.of("vehicle-value", "latest-value");
                case "show_history" -> Set.of("vehicle-timeline", "time-series");
                default -> Set.of();
            };
            result.put(operation, choices.stream().filter(Map.class::isInstance).map(Map.class::cast)
                    .filter(choice -> types.isEmpty() || choice.get("views") instanceof List<?> views
                            && views.stream().anyMatch(item -> item instanceof Map<?, ?> view && types.contains(view.get("widgetType"))))
                    .map(choice -> choice.get("dataRef")).filter(String.class::isInstance).map(String.class::cast).distinct().toList());
        }
        return Map.copyOf(result);
    }
}
