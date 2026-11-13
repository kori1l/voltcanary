package com.voltcanary.aiagent.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Relational schema variants keep target-specific constraints visible to native tools. */
final class DashboardOperationConstraints {
    private DashboardOperationConstraints() { }

    static Map<String, Object> replacements(Map<String, Object> envelope, Map<String, Object> context) {
        var properties = copy((Map<?, ?>) envelope.get("properties"));
        var operations = copy((Map<?, ?>) properties.get("operations"));
        var items = copy((Map<?, ?>) operations.get("items"));
        var variants = new ArrayList<Map<String, Object>>();
        for (var item : (List<?>) items.get("anyOf")) {
            var variant = copy((Map<?, ?>) item);
            var fields = (Map<?, ?>) variant.get("properties");
            if (!List.of("set_data").equals(((Map<?, ?>) fields.get("op")).get("enum"))) {
                variants.add(variant);
                continue;
            }
            var groups = new LinkedHashMap<List<String>, List<String>>();
            for (var widget : WidgetDataCompatibility.widgets(context)) {
                // A preceding show_history may change view in the same batch. Type stays fixed;
                // the ordered pre-check and final compiler validate the effective visualization.
                var refs = WidgetDataCompatibility.typeChoices(context, widget);
                if (!refs.isEmpty() && widget.get("key") instanceof String key)
                    groups.computeIfAbsent(refs, ignored -> new ArrayList<>()).add(key);
            }
            groups.forEach((refs, keys) -> {
                var selected = copy(fields);
                selected.put("widgetKey", Map.of("type", "string", "enum", keys));
                selected.put("dataRef", Map.of("type", "string", "enum", refs));
                var replacement = copy(variant);
                replacement.put("properties", selected);
                variants.add(replacement);
            });
        }
        items.put("anyOf", variants);
        operations.put("items", items);
        properties.put("operations", operations);
        var result = copy(envelope);
        result.put("properties", properties);
        return result;
    }

    private static Map<String, Object> copy(Map<?, ?> source) {
        var result = new LinkedHashMap<String, Object>();
        source.forEach((key, value) -> result.put((String) key, value));
        return result;
    }
}
