package com.voltcanary.aiagent.service;

import com.voltcanary.aiagent.service.AuthorizedPlanningContext.Prepared;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Resolves exact server-issued data choices; model-supplied identities never grant access. */
final class DashboardDataReferences {
    private DashboardDataReferences() { }

    static Map<?, ?> resolve(Map<?, ?> panel, Prepared context) {
        Object token = panel.get("dataRef");
        if (token == null) {
            var normalized = DashboardVehicleFields.normalize(panel, context.context());
            Object selection = normalized.get("deviceRefs") == null ? context.context().get("selectedDashboardDeviceRefs") : normalized.get("deviceRefs");
            if (!preservesBinding(normalized, context.context())) for (var candidate : context.refs()) {
                var choice = candidate.summary();
                if (candidate.kind().equals("dashboard-data") && Boolean.TRUE.equals(choice.get("requiresSourceChoice"))
                        && selection instanceof List<?> selected && selected.contains(choice.get("deviceRef"))
                        && Objects.equals(normalized.get("vehicleField"), choice.get("key"))
                        && choice.get("views") instanceof List<?> views && views.stream().anyMatch(item -> item instanceof Map<?, ?> view
                            && Objects.equals(normalized.get("type"), view.get("widgetType")))) {
                    throw new DashboardSourceChoiceRequired(choice, context);
                }
            }
            return normalized;
        }
        var ref = context.refs().stream().filter(value -> value.kind().equals("dashboard-data") && value.ref().equals(token))
                .findFirst().orElseThrow(() -> rejected("Data choice is expired or unavailable; use a current dataRef"));
        var choice = ref.summary();
        if (Boolean.TRUE.equals(choice.get("requiresSourceChoice"))) throw new DashboardSourceChoiceRequired(choice, context);
        String type = String.valueOf(panel.get("type"));
        if (!WidgetDataCompatibility.supports(choice, panel)) {
            throw rejected("Selected dataRef is incompatible with the widget/view. Use one of its offered views");
        }
        var device = context.refs().stream().filter(value -> value.kind().equals("device") && value.resourceId().equals(choice.get("deviceId"))
                && (choice.get("deviceRef") == null || value.ref().equals(choice.get("deviceRef"))))
                .findFirst().orElseThrow(() -> rejected("Selected data device is unavailable"));
        if (panel.get("deviceRefs") != null && !List.of(device.ref()).equals(panel.get("deviceRefs"))) throw rejected("dataRef belongs to a different device selection");
        var normalized = new LinkedHashMap<Object, Object>(panel);
        normalized.remove("dataRef");
        normalized.put("_resolvedDataChoiceId", ref.resourceId());
        normalized.put("deviceRefs", List.of(device.ref()));
        if ("REGISTERED_NUMERIC_METRIC".equals(choice.get("family"))) {
            exact(panel, "metric", choice.get("key"));
            exact(panel, "unit", choice.get("unit"));
            if (panel.get("vehicleField") != null || panel.get("sourceRef") != null) throw rejected("Registered metric choice cannot be changed into a source field");
            normalized.put("metric", choice.get("key"));
            normalized.put("unit", choice.get("unit"));
        } else if ("RECORDED_VEHICLE_FIELD".equals(choice.get("family"))) {
            if (panel.get("metric") != null || panel.get("unit") != null || panel.get("reducer") != null) throw rejected("Recorded fields use their declared type/unit, not registered metric arguments");
            exact(panel, "vehicleField", choice.get("key"));
            if (choice.get("key") != null) normalized.put("vehicleField", choice.get("key"));
            var source = context.refs().stream().filter(value -> value.kind().equals("source") && value.resourceId().equals(choice.get("sourceId")))
                    .findFirst().orElseThrow(() -> rejected("Selected data source is unavailable"));
            exact(panel, "sourceRef", source.ref());
            normalized.put("sourceRef", source.ref());
        }
        return normalized;
    }

    private static void exact(Map<?, ?> panel, String key, Object expected) {
        if (panel.get(key) != null && !Objects.equals(panel.get(key), expected)) throw rejected("Do not override " + key + " selected by dataRef");
    }

    private static boolean preservesBinding(Map<?, ?> panel, Map<String, Object> context) {
        if (!(context.get("previousDashboardIntent") instanceof Map<?, ?> previous) || !(previous.get("widgets") instanceof List<?> widgets)) return false;
        return widgets.stream().anyMatch(item -> item instanceof Map<?, ?> original && panel.get("sourceRef") != null
                && List.of("key", "type", "sourceRef", "vehicleField", "deviceRefs").stream().allMatch(key -> Objects.equals(panel.get(key), original.get(key))));
    }

    private static DashboardPreparationRejected rejected(String reason) {
        return new DashboardPreparationRejected(reason);
    }
}
