package com.voltcanary.aiagent.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Compiler-derived postconditions, ready for the separate outcome verifier. */
final class DashboardOperationExpectations {

    private DashboardOperationExpectations() {}

    static List<Map<String, Object>> derive(
        Map<String, Object> intent,
        Map<String, Set<String>> mask,
        AuthorizedPlanningContext.Prepared context
    ) {
        var result = new ArrayList<Map<String, Object>>();
        List<?> widgets = (List<?>) intent.get("widgets");
        mask.forEach((key, fields) -> {
            var expected = new LinkedHashMap<String, Object>();
            Map<?, ?> candidate = key.equals("$dashboard")
                ? intent
                : widgets
                      .stream()
                      .map(panel -> (Map<?, ?>) panel)
                      .filter(panel -> key.equals(panel.get("key")))
                      .findFirst()
                      .orElse(Map.of());
            if (candidate.containsKey("dataRef")) candidate = DashboardDataReferences.resolve(
                candidate,
                context
            );
            for (String field : fields) {
                if (field.equals("removed")) expected.put("present", false);
                else if (field.equals("created")) expected.put("widget", candidate);
                else if (field.equals("order")) expected.put(
                    "order",
                    widgets
                        .stream()
                        .map(panel -> ((Map<?, ?>) panel).get("key"))
                        .toList()
                );
                else if (field.startsWith("settings.")) expected.put(
                    field,
                    candidate.get("settings") instanceof Map<?, ?> settings
                        ? settings.get(field.substring(9))
                        : null
                );
                else expected.put(field, candidate.get(field));
            }
            result.add(Map.of("target", key, "expected", expected));
        });
        return List.copyOf(result);
    }
}
