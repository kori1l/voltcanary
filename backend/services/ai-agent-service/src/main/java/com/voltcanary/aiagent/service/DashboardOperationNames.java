package com.voltcanary.aiagent.service;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Extracts one bounded name for user grounding before unrelated operation preparation can fail. */
final class DashboardOperationNames {
    private DashboardOperationNames() { }

    static String proposed(Map<String, Object> parameters, boolean operationsEnabled) {
        if (!parameters.containsKey("protocol")) return parameters.get("name") instanceof String name ? name : null;
        if (!operationsEnabled || !DashboardOperationProtocol.VERSION.equals(parameters.get("protocol"))
                || !parameters.keySet().equals(Set.of("protocol", "operations"))
                || !(parameters.get("operations") instanceof List<?> operations) || operations.size() > 24) return null;
        var names = operations.stream().filter(item -> item instanceof Map<?, ?> operation && "rename_dashboard".equals(operation.get("op")))
                .map(item -> (Map<?, ?>) item).toList();
        if (names.size() != 1) return null;
        var operation = names.getFirst();
        if (!operation.keySet().equals(Set.of("op", "id", "name")) || !(operation.get("id") instanceof String id)
                || !id.matches("[A-Za-z][A-Za-z0-9_-]{0,39}") || !(operation.get("name") instanceof String name)
                || name.isBlank() || name.length() > 80) return null;
        return name;
    }
}
