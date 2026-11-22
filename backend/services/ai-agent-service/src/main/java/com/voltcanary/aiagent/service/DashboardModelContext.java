package com.voltcanary.aiagent.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Keep legacy validation context server-side, without competing template instructions in model input. */
final class DashboardModelContext {

    private DashboardModelContext() {}

    static Map<String, Object> forModel(Map<String, Object> context) {
        var result = new LinkedHashMap<>(context);
        result.remove("dashboardTurnId");
        result.put("taskMemory", TaskConversationMemory.facts(context));
        if (!context.containsKey("dashboardWidgetTypes")) return Map.copyOf(result);
        result.remove("dashboardTemplates");
        if (
            context.get("previousDashboardIntent") instanceof Map<?, ?> previous &&
            previous.get("widgets") instanceof List<?> widgets
        ) {
            var hint = new LinkedHashMap<String, Object>();
            for (String key : List.of("name", "period", "deviceRefs"))
                if (previous.get(key) != null) hint.put(key, previous.get(key));
            var choices = new ArrayList<Map<String, Object>>();
            for (Object item : widgets)
                if (item instanceof Map<?, ?> widget) {
                    var copy = new LinkedHashMap<String, Object>();
                    widget.forEach((key, value) -> {
                        if (key instanceof String field && value != null) copy.put(field, value);
                    });
                    // A redundant local period should inherit the global period on later global edits.
                    if (
                        java.util.Objects.equals(copy.get("period"), previous.get("period"))
                    ) copy.remove("period");
                    choices.add(copy);
                }
            hint.put("widgets", choices);
            result.put("previousDashboardIntent", hint);
        }
        return result;
    }
}
