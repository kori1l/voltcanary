package com.voltcanary.aiagent.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Remove duplicate legacy catalogues before applying the unchanged input budget. */
final class DashboardOperationContext {
    private DashboardOperationContext() { }

    static void compact(Map<String, Object> context) {
        context.remove("dashboardTemplates");
        if (context.get("dashboardDevices") instanceof List<?> devices) {
            context.put("dashboardDevices", devices.stream().map(item -> {
                var device = new LinkedHashMap<String, Object>();
                if (item instanceof Map<?, ?> value) value.forEach((key, field) -> {
                    if (key instanceof String name && !List.of("metrics", "fields").contains(name)) device.put(name, field);
                });
                return device;
            }).toList());
        }
    }
}
