package com.voltcanary.aiagent.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Adapts redundant metric-shaped source-field parameters without changing the requested widget type. */
final class DashboardVehicleFields {
    private DashboardVehicleFields() { }

    static Map<?, ?> normalize(Map<?, ?> panel, Map<String, Object> context) {
        if (!"vehicle-value".equals(panel.get("type")) && !"vehicle-timeline".equals(panel.get("type"))) return panel;
        Object field = panel.get("vehicleField") == null ? panel.get("metric") : panel.get("vehicleField");
        if (!(field instanceof String) || panel.get("metric") != null && !Objects.equals(field, panel.get("metric"))) return panel;
        if (panel.get("reducer") != null && !"RAW".equals(panel.get("reducer"))) return panel;
        if (context.get("dashboardDataChoices") instanceof List<?> choices) {
            Object selected = panel.get("deviceRefs") == null ? context.get("selectedDashboardDeviceRefs") : panel.get("deviceRefs");
            var matching = choices.stream().filter(item -> item instanceof Map<?, ?> choice
                    && "RECORDED_VEHICLE_FIELD".equals(choice.get("family")) && Objects.equals(field, choice.get("key"))
                    && selected instanceof List<?> ids && ids.size() == 1 && ids.contains(choice.get("deviceRef"))
                    && (panel.get("sourceRef") == null || Objects.equals(panel.get("sourceRef"), choice.get("sourceRef"))))
                    .map(item -> (Map<?, ?>) item).toList();
            if (matching.size() != 1) return panel;
            var choice = matching.getFirst();
            if (panel.get("unit") != null && !"".equals(panel.get("unit")) && !Objects.equals(panel.get("unit"), choice.get("unit"))) return panel;
            var normalized = new LinkedHashMap<Object, Object>(panel);
            normalized.put("vehicleField", field);
            if (choice.get("sourceRef") != null) normalized.put("sourceRef", choice.get("sourceRef"));
            normalized.remove("metric");
            normalized.remove("unit");
            normalized.remove("reducer");
            return normalized;
        }
        // Compatibility for legacy callers without a resolved capability catalogue.
        if (!(context.get("dashboardDevices") instanceof List<?> devices)) return panel;
        Object selection = panel.get("deviceRefs") == null ? context.get("selectedDashboardDeviceRefs") : panel.get("deviceRefs");
        for (Object item : devices) if (item instanceof Map<?, ?> device && selection instanceof List<?> refs && refs.contains(device.get("ref"))
                && device.get("fields") instanceof List<?> fields) {
            for (Object value : fields) if (value instanceof Map<?, ?> recorded && Objects.equals(field, recorded.get("key"))) {
                Object unit = panel.get("unit");
                if (unit != null && !"".equals(unit) && !Objects.equals(unit, recorded.get("unit"))) return panel;
                var normalized = new LinkedHashMap<Object, Object>(panel);
                normalized.put("vehicleField", field);
                normalized.remove("metric");
                normalized.remove("unit");
                normalized.remove("reducer");
                return normalized;
            }
        }
        return panel;
    }
}
