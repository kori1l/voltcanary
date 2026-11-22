package com.voltcanary.aiagent.service;

import com.voltcanary.aiagent.model.PlanningQuestion;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Working task values, separate from chat wording and transient model resource refs. */
final class TaskConversationMemory {

    private TaskConversationMemory() {}

    static Map<String, Object> facts(Map<String, Object> context) {
        var values = new LinkedHashMap<String, Object>();
        if (context.get("previousDashboardIntent") instanceof Map<?, ?> intent) {
            intent.forEach((key, value) -> {
                if (key instanceof String field && value != null) values.put(field, value);
            });
        }
        for (String key : List.of(
            "name",
            "type",
            "manufacturer",
            "model",
            "profile",
            "protocol",
            "metric",
            "from",
            "to"
        )) {
            if (context.get(key) != null) values.put(key, context.get(key));
        }
        if (
            context.get("selectedDashboardDeviceRefs") instanceof List<?> selected &&
            !selected.isEmpty()
        ) values.put("deviceRefs", selected);
        if (context.get("selectedDashboardSourceRef") instanceof String selected) values.put(
            "sourceId",
            selected
        );
        return Map.copyOf(values);
    }

    static boolean asksKnown(PlanningQuestion question, Map<String, Object> context) {
        if (question == null || !question.bounded()) return false;
        var known = facts(context);
        return question
            .missingFields()
            .stream()
            .anyMatch(field -> {
                Object value = known.get(field);
                if (value instanceof String text) return !text.isBlank();
                if (value instanceof java.util.Collection<?> items) return !items.isEmpty();
                if (value instanceof Map<?, ?> entries) return !entries.isEmpty();
                return value != null;
            });
    }
}
