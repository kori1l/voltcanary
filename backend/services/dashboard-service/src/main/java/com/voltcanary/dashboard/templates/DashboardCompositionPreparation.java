package com.voltcanary.dashboard.templates;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voltcanary.dashboard.domain.DashboardActor;
import com.voltcanary.dashboard.domain.DashboardDefinition;
import com.voltcanary.dashboard.domain.DashboardFailure;
import com.voltcanary.dashboard.port.DashboardOwnership;
import com.voltcanary.dashboard.port.TemplateDeviceCatalogue;
import com.voltcanary.dashboard.query.WidgetManifests;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Compiles bounded widget choices through the same contracts as the ordinary editor. */
@Service
public class DashboardCompositionPreparation {
    public record Panel(String key, String type, String title, List<UUID> deviceIds, String metric,
            String unit, UUID sourceId, String visualization, String reducer, String text,
            String width, Integer height, String period, Boolean showTitle, Boolean showMapDetails, String vehicleField, Boolean compact, Boolean showMapNotices,
            Map<String, Object> settings, String dataChoiceId) {
        public Panel(String key, String type, String title, List<UUID> deviceIds, String metric,
                String unit, UUID sourceId, String visualization, String reducer, String text,
                String width, Integer height, String period, Boolean showTitle, Boolean showMapDetails, String vehicleField, Boolean compact, Boolean showMapNotices,
                Map<String, Object> settings) {
            this(key, type, title, deviceIds, metric, unit, sourceId, visualization, reducer, text,
                    width, height, period, showTitle, showMapDetails, vehicleField, compact, showMapNotices, settings, null);
        }
        public Panel(String key, String type, String title, List<UUID> deviceIds, String metric,
                String unit, UUID sourceId, String visualization, String reducer, String text,
                String width, Integer height, String period, Boolean showTitle, Boolean showMapDetails, String vehicleField, Boolean compact, Boolean showMapNotices) {
            this(key, type, title, deviceIds, metric, unit, sourceId, visualization, reducer, text,
                    width, height, period, showTitle, showMapDetails, vehicleField, compact, showMapNotices, null);
        }
        public Panel(String key, String type, String title, List<UUID> deviceIds, String metric,
                String unit, UUID sourceId, String visualization, String reducer, String text,
                String width, Integer height, String period, Boolean showTitle, Boolean showMapDetails, String vehicleField, Boolean compact) {
            this(key, type, title, deviceIds, metric, unit, sourceId, visualization, reducer, text,
                    width, height, period, showTitle, showMapDetails, vehicleField, compact, null);
        }
        public Panel(String key, String type, String title, List<UUID> deviceIds, String metric,
                String unit, UUID sourceId, String visualization, String reducer, String text,
                String width, Integer height, String period, Boolean showTitle, Boolean showMapDetails, String vehicleField) {
            this(key, type, title, deviceIds, metric, unit, sourceId, visualization, reducer, text,
                    width, height, period, showTitle, showMapDetails, vehicleField, null);
        }
        public Panel(String key, String type, String title, List<UUID> deviceIds, String metric,
                String unit, UUID sourceId, String visualization, String reducer, String text,
                String width, Integer height, String period) {
            this(key, type, title, deviceIds, metric, unit, sourceId, visualization, reducer, text,
                    width, height, period, null, null, null, null);
        }
    }
    public record Request(String name, String period, List<UUID> deviceIds, List<Panel> widgets) { }
    public record Prepared(String status, String digest, DashboardDefinition definition,
            List<String> diagnostics, List<Map<String, Object>> devices) { }
    private static final Set<String> PERIODS = Set.of("LAST_1_HOUR", "LAST_6_HOURS", "LAST_24_HOURS", "LAST_7_DAYS", "LAST_30_DAYS");
    private final DashboardOwnership ownership;
    private final TemplateDeviceCatalogue catalogue;
    private final WidgetManifests manifests;
    private final TemplateDefinitionChecks checks;
    private final DashboardTemplateAssembler digests;
    private final ObjectMapper mapper;
    private final com.voltcanary.dashboard.application.DashboardCapabilityResolution capabilities;

    public DashboardCompositionPreparation(DashboardOwnership ownership, TemplateDeviceCatalogue catalogue,
            WidgetManifests manifests, TemplateDefinitionChecks checks, DashboardTemplateAssembler digests, ObjectMapper mapper,
            com.voltcanary.dashboard.application.DashboardCapabilityResolution capabilities) {
        this.ownership = ownership;
        this.catalogue = catalogue;
        this.manifests = manifests;
        this.checks = checks;
        this.digests = digests;
        this.mapper = mapper;
        this.capabilities = capabilities;
    }

    public Prepared prepare(DashboardActor actor, String authorization, Request request) {
        if (request == null || request.name() == null || request.name().isBlank() || request.name().length() > 80
                || request.deviceIds() == null || request.deviceIds().isEmpty() || request.deviceIds().size() > 20
                || request.deviceIds().stream().anyMatch(java.util.Objects::isNull) || Set.copyOf(request.deviceIds()).size() != request.deviceIds().size()
                || request.widgets() == null || request.widgets().isEmpty() || request.widgets().size() > 12) {
            throw invalid("Choose a name, 1–20 devices and 1–12 widgets");
        }
        String period = request.period() == null ? "LAST_24_HOURS" : request.period();
        if (!PERIODS.contains(period)) return question("Выберите поддерживаемый относительный период.");
        // Authorize the whole selection before retrieving any labels or measurements.
        for (UUID id : request.deviceIds()) if (!ownership.canReadDevice(authorization, id, actor)) {
            throw new DashboardFailure("NO_ACCESS", "Composition binding is unavailable");
        }
        Map<UUID, TemplateDeviceCatalogue.Device> devices = new LinkedHashMap<>();
        for (UUID id : request.deviceIds()) devices.put(id, catalogue.read(authorization, id));
        var resolvedData = capabilities.resolve(actor, authorization,
                new com.voltcanary.dashboard.application.DashboardCapabilityResolution.Request(request.deviceIds(), "LAST_1_HOUR", null));
        List<DashboardDefinition.Widget> widgets = new ArrayList<>();
        var keys = new java.util.HashSet<String>();
        for (Panel panel : request.widgets()) {
            if (panel == null || panel.key() == null || !panel.key().matches("[A-Za-z][A-Za-z0-9_-]{0,59}") || !keys.add(panel.key())
                    || panel.type() == null || manifests.find(panel.type(), 1).filter(item -> item.supported()).isEmpty()
                    || panel.title() == null || panel.title().isBlank() || panel.title().length() > 120
                    || panel.width() != null && !Set.of("FULL", "HALF", "THIRD").contains(panel.width())
                    || panel.height() != null && (panel.height() < 1 || panel.height() > 12)) throw invalid("Invalid widget type, key, title or size");
            List<UUID> ids = panel.deviceIds() == null ? request.deviceIds() : panel.deviceIds();
            if (ids.isEmpty() || ids.stream().anyMatch(java.util.Objects::isNull) || Set.copyOf(ids).size() != ids.size() || !devices.keySet().containsAll(ids)) {
                throw new DashboardFailure("NO_ACCESS", "Widget selection must belong to the authorized composition");
            }
            String widgetPeriod = panel.period() == null ? period : panel.period();
            if (!PERIODS.contains(widgetPeriod)) return question(panel.title() + ": выберите поддерживаемый период.");
            ObjectNode query = mapper.createObjectNode();
            query.set("time", time(widgetPeriod));
            ObjectNode presentation = mapper.createObjectNode().put("showTitle", panel.showTitle() == null ? !panel.type().equals("vehicle-map") : panel.showTitle());
            if (panel.compact() != null) presentation.put("compact", panel.compact());
            ObjectNode payload = mapper.createObjectNode();
            if (panel.visualization() != null) {
                if (!visualizations(panel.type()).contains(panel.visualization())) return question(panel.title() + ": этот вид отображения несовместим с виджетом.");
                presentation.put("visualization", panel.visualization());
            }
            capabilities.requireCompatible(resolvedData, panel.type(), ids, panel.sourceId(), panel.metric(), panel.unit(), panel.vehicleField(), panel.visualization(), panel.dataChoiceId());
            boolean measured = Set.of("time-series", "latest-value", "metric-summary").contains(panel.type());
            if (measured) {
                if (panel.metric() == null || panel.unit() == null || panel.metric().startsWith("var_")) return question(panel.title() + ": выберите зарегистрированную метрику и единицу; source-bound переменные настраиваются в редакторе.");
                if (panel.type().equals("latest-value") && ids.size() != 1) return question(panel.title() + ": выберите одно устройство для текущего значения.");
                for (UUID id : ids) if (devices.get(id).metrics().stream().noneMatch(metric -> metric.name().equals(panel.metric()) && metric.unit().equals(panel.unit()))) {
                    return question(devices.get(id).name() + ": метрика " + panel.metric() + " (" + panel.unit() + ") недоступна. Выберите зарегистрированную метрику.");
                }
                query.put("metric", panel.metric()).put("unit", panel.unit());
                query.put("reducer", panel.reducer() == null ? (panel.type().equals("metric-summary") ? "MEAN" : "RAW") : panel.reducer());
                if (panel.type().equals("time-series")) query.set("series", mapper.valueToTree(ids.stream().map(id -> Map.of("deviceId", id, "metric", panel.metric())).toList()));
            } else if (panel.metric() != null || panel.unit() != null || panel.reducer() != null) {
                return question(panel.title() + ": параметры измерений не подходят этому виджету.");
            }
            if (com.voltcanary.dashboard.query.PositionWidgets.contains(panel.type())) {
                if (!panel.type().equals("vehicle-map") && (ids.size() != 1 || panel.vehicleField() == null
                        || !panel.vehicleField().matches("[A-Za-z][A-Za-z0-9_.]{0,99}"))) {
                    return question(panel.title() + ": выберите одно транспортное средство и поле источника.");
                }
                if (panel.vehicleField() != null) presentation.put("vehicleField", panel.vehicleField());
                if (panel.showMapNotices() != null) presentation.put("showMapNotices", panel.showMapNotices());
                presentation.put("showMapDetails", panel.showMapDetails() != null && panel.showMapDetails());
                if (Set.of("LAST_7_DAYS", "LAST_30_DAYS").contains(widgetPeriod)) return question("Для карты выберите период до24 часов; графики могут использовать более длинный период отдельно.");
                var sources = new LinkedHashMap<String, String>();
                for (UUID id : ids) {
                    var device = devices.get(id);
                    if (!device.type().equals("VEHICLE")) return question(device.name() + ": карта движения требует транспортное средство.");
                    var available = device.sources().stream().filter(source -> panel.sourceId() == null || panel.sourceId().equals(source.id())).toList();
                    if (available.size() != 1) return question(device.name() + ": выберите один зарегистрированный источник координат.");
                    sources.put(id.toString(), available.getFirst().sourceId());
                }
                if (ids.size() == 1) query.put("sourceId", sources.values().iterator().next());
                else query.set("vehicleSources", mapper.valueToTree(sources));
                presentation.put("follow", true);
            } else if (panel.sourceId() != null) {
                return question(panel.title() + ": выбор отдельного источника поддерживается картой; для source-bound графика используйте редактор переменных.");
            }
            if (panel.text() != null && (!panel.type().equals("context-note") || panel.text().length() > 1000)) throw invalid("Only a note may contain bounded text");
            if (panel.type().equals("context-note")) payload.put("text", panel.text() == null ? "" : panel.text());
            if (panel.type().equals("incident-list")) query.put("incidentMode", "ACTIVE");
            if (panel.settings() != null) {
                JsonNode settings = mapper.valueToTree(panel.settings());
                com.voltcanary.dashboard.domain.WidgetCapabilities.validate(panel.type(), settings, true);
                for (String legacy : List.of("showTitle", "showMapDetails", "showMapNotices", "compact")) {
                    Object value = switch (legacy) {
                        case "showTitle" -> panel.showTitle();
                        case "showMapDetails" -> panel.showMapDetails();
                        case "showMapNotices" -> panel.showMapNotices();
                        default -> panel.compact();
                    };
                    if (value != null && settings.has(legacy)) throw invalid("Do not supply the same setting in both settings and legacy fields");
                }
                settings.fields().forEachRemaining(entry -> {
                    if (!entry.getValue().isNull()) presentation.set(entry.getKey(), entry.getValue());
                    else {
                        Object fallback = com.voltcanary.dashboard.domain.WidgetCapabilities.forType(panel.type()).settings().stream()
                                .filter(setting -> setting.key().equals(entry.getKey())).findFirst()
                                .map(com.voltcanary.dashboard.domain.WidgetCapabilities.Setting::defaultValue).orElse(null);
                        if (fallback == null) presentation.remove(entry.getKey());
                        else presentation.set(entry.getKey(), mapper.valueToTree(fallback));
                    }
                });
            }
            widgets.add(new DashboardDefinition.Widget(panel.key(), panel.type(), 1, panel.title(), scope(ids), query,
                    presentation, mapper.createObjectNode(), payload));
        }
        ObjectNode defaults = mapper.createObjectNode();
        defaults.set("scope", scope(request.deviceIds()));
        defaults.set("time", time(period));
        DashboardDefinition definition = new DashboardDefinition(1, request.name().trim(), "", defaults,
                List.of(), List.copyOf(widgets), layouts(request.widgets()));
        checks.validate(actor, authorization, definition);
        var labels = devices.values().stream().map(device -> Map.<String, Object>of("id", device.id(), "name", device.name(),
                "type", device.type(), "sourceIds", device.sources().stream().map(TemplateDeviceCatalogue.Source::id).toList())).toList();
        return new Prepared("READY", digests.digest(definition), definition, List.of(), labels);
    }

    private com.fasterxml.jackson.databind.JsonNode layouts(List<Panel> panels) {
        var layouts = new LinkedHashMap<String, Object>();
        for (String breakpoint : List.of("desktop", "tablet", "mobile")) {
            int columns = breakpoint.equals("desktop") ? 12 : breakpoint.equals("tablet") ? 6 : 1;
            int x = 0, y = 0, row = 0;
            List<Map<String, Object>> placements = new ArrayList<>();
            for (Panel panel : panels) {
                int width = new DashboardTemplateLayout().width(new DashboardTemplateLayout.Panel(panel.key(), panel.width(), panel.height()), columns);
                int height = panel.height() == null ? 4 : panel.height();
                if (x + width > columns) { y += row; x = 0; row = 0; }
                placements.add(Map.of("widgetId", panel.key(), "x", x, "y", y, "w", width, "h", height));
                x += width;
                row = Math.max(row, height);
            }
            layouts.put(breakpoint, placements);
        }
        return mapper.valueToTree(layouts);
    }

    public static List<String> visualizations(String type) {
        return com.voltcanary.dashboard.domain.VisualizationCompatibility.choices(type);
    }

    private ObjectNode scope(List<UUID> ids) {
        ObjectNode scope = mapper.createObjectNode().put("mode", "FIXED");
        scope.set("deviceIds", mapper.valueToTree(ids));
        return scope;
    }
    private ObjectNode time(String period) { return mapper.createObjectNode().put("mode", "RELATIVE").put("preset", period).put("timezone", "UTC"); }
    private Prepared question(String message) { return new Prepared("NEEDS_INPUT", null, null, List.of(message), List.of()); }
    private DashboardFailure invalid(String message) { return new DashboardFailure("VALIDATION_ERROR", message, "widgets"); }
}
