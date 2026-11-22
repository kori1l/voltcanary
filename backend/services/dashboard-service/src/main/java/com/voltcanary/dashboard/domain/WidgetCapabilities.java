package com.voltcanary.dashboard.domain;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Presentation and data capabilities shared by the editor, composition and AI catalogue. */
public final class WidgetCapabilities {

    private static final int MAX_SETTINGS = 32;
    private static final int MAX_FIELDS = 50;
    private static final String FIELD_KEY_PATTERN = "^[A-Za-z][A-Za-z0-9_.]{0,99}$";

    public record Choice(String value, String label) {}

    public record Setting(
        String key,
        String type,
        String section,
        String group,
        String label,
        Object defaultValue,
        List<Choice> choices,
        Integer maxLength
    ) {}

    public record Binding(
        List<String> modes,
        int maxDevices,
        boolean sourceRequired,
        boolean metricRequired
    ) {}

    public record Descriptor(
        int version,
        String dataKind,
        List<String> valueTypes,
        Binding binding,
        List<String> visualizations,
        Map<String, List<String>> visualizationValueTypes,
        List<Setting> settings
    ) {
        @com.fasterxml.jackson.annotation.JsonProperty("settingsSchema")
        public Map<String, Object> settingsSchema() {
            var properties = new LinkedHashMap<String, Object>();
            for (var setting : settings) properties.put(setting.key(), settingSchema(setting));
            // Historical null extension keys are ignored by the existing strict validator.
            return Map.of(
                "type",
                "object",
                "maxProperties",
                MAX_SETTINGS,
                "properties",
                properties,
                "additionalProperties",
                Map.of("type", "null")
            );
        }
    }

    private WidgetCapabilities() {}

    private static Map<String, Object> settingSchema(Setting setting) {
        var schema = new LinkedHashMap<String, Object>();
        schema.put("description", setting.label());
        schema.put(
            "type",
            List.of(
                switch (setting.type()) {
                    case "BOOLEAN", "AUTO_BOOLEAN" -> "boolean";
                    case "FIELD_SET" -> "array";
                    default -> "string";
                },
                "null"
            )
        );
        schema.put("default", setting.defaultValue());
        if (setting.type().equals("SELECT")) {
            var choices = new ArrayList<Object>(
                setting.choices().stream().map(Choice::value).toList()
            );
            choices.add(null);
            schema.put("enum", choices);
        }
        if (setting.type().equals("TEXT")) schema.put("maxLength", setting.maxLength());
        if (setting.type().equals("FIELD_SET")) {
            schema.put("maxItems", MAX_FIELDS);
            schema.put(
                "items",
                Map.of(
                    "type",
                    "string",
                    "maxLength",
                    setting.maxLength(),
                    "pattern",
                    FIELD_KEY_PATTERN
                )
            );
        }
        return schema;
    }

    public static String description(String type) {
        return switch (type) {
            case "vehicle-value" -> "Latest recorded vehicle field: numeric value, boolean state or text; not historical trends.";
            case "vehicle-timeline" -> "Recorded vehicle field history. Line charts support numbers and booleans; lists also support text observations.";
            case "vehicle-map" -> "Vehicle GPS coordinates, movement trail and map. Controls, journey details, stale-data notices and tooltip fields are separate settings.";
            case "fleet-count" -> "Registered fleet counts and device freshness at the current snapshot.";
            case "latest-value" -> "Latest registered numeric metric measurement for one device, with optional compact presentation.";
            case "time-series" -> "Numeric measurement trends over a time interval, with raw, average, minimum or maximum series.";
            case "metric-summary" -> "Numeric measurement statistics over an interval, including minimum, maximum, mean and percentile.";
            case "incident-list" -> "Recorded incidents and their timestamps; snapshot or incident creation interval.";
            case "maintenance-agenda" -> "Scheduled maintenance in an explicit interval, with optional alert and analytics exclusion columns.";
            case "risk-list" -> "Recorded risk evidence; does not generate new predictions.";
            case "quality-and-freshness" -> "Measurement quality, age and freshness evidence at snapshot and interval.";
            case "location-matrix" -> "Location evidence within the current account, with time basis per column.";
            case "prediction-evidence" -> "Recorded failure predictions, sortable by recency or risk, with optional model and calculation time.";
            case "context-note" -> "Operator-provided text context; does not represent measured telemetry.";
            case "energy-period" -> "Energy consumption, coverage and supported period comparison over an interval.";
            case "peak-and-ranking" -> "Energy and recorded equipment peak ranking over an interval.";
            case "estimated-cost" -> "Estimated energy cost over an interval; not a billing receipt.";
            case "uptime-or-cycle" -> "Confirmed operational periods and cycle evidence, with optional compact operations summary.";
            default -> "No registered capability description; consult the widget support status.";
        };
    }

    public static Descriptor forType(String type) {
        boolean vehicle = List.of("vehicle-map", "vehicle-value", "vehicle-timeline").contains(
            type
        );
        boolean metric = List.of("latest-value", "time-series", "metric-summary").contains(type);
        String dataKind = vehicle
            ? "RECORDED_VEHICLE_FIELD"
            : metric
              ? "REGISTERED_NUMERIC_METRIC"
              : type.equals("context-note")
                ? "OPERATOR_TEXT"
                : "DOMAIN_EVIDENCE";
        List<String> values = type.equals("vehicle-map")
            ? List.of("coordinates")
            : vehicle
              ? List.of("number", "boolean", "string")
              : metric
                ? List.of("number")
                : type.equals("context-note")
                  ? List.of("string")
                  : List.of("domain-facts");
        Binding binding = new Binding(
            vehicle || metric ? List.of("FIXED") : List.of("ALL", "DYNAMIC", "FIXED", "UNBOUND"),
            List.of("vehicle-value", "vehicle-timeline", "latest-value").contains(type)
                ? 1
                : type.equals("time-series")
                  ? 8
                  : 20,
            vehicle,
            metric
        );
        var settings = new ArrayList<Setting>();
        settings.add(flag("showTitle", "GENERAL", "Header", "Show panel title", false));
        settings.add(
            flag(
                "showDetails",
                "GENERAL",
                "Evidence details",
                "Show measurement details below the panel",
                false
            )
        );
        if (
            List.of(
                "time-series",
                "fleet-count",
                "quality-and-freshness",
                "energy-period",
                "vehicle-value",
                "latest-value",
                "uptime-or-cycle"
            ).contains(type)
        ) {
            settings.add(
                flag(
                    "showLegend",
                    "APPEARANCE",
                    "Labels and legend",
                    "Show device and metric labels",
                    false
                )
            );
        }
        if (
            List.of(
                "time-series",
                "fleet-count",
                "quality-and-freshness",
                "energy-period"
            ).contains(type)
        ) settings.add(flag("showAxes", "APPEARANCE", "Chart", "Show chart axes", true));
        if (
            List.of(
                "fleet-count",
                "quality-and-freshness",
                "latest-value",
                "metric-summary",
                "energy-period",
                "peak-and-ranking",
                "estimated-cost",
                "uptime-or-cycle"
            ).contains(type)
        ) {
            settings.add(
                flag(
                    "showCalculation",
                    "APPEARANCE",
                    "Explanations",
                    "Show calculation explanations",
                    false
                )
            );
        }
        if (List.of("energy-period", "metric-summary").contains(type)) settings.add(
            flag("showComparison", "APPEARANCE", "Comparison", "Show period comparison", false)
        );
        if (List.of("vehicle-value", "latest-value", "metric-summary").contains(type)) {
            settings.add(
                new Setting(
                    "compact",
                    "AUTO_BOOLEAN",
                    "APPEARANCE",
                    "Value card",
                    "Value card layout",
                    null,
                    List.of(
                        new Choice("auto", "Automatic — compact in small cards"),
                        new Choice("true", "Compact — value and short status"),
                        new Choice("false", "Full — measurement details")
                    ),
                    null
                )
            );
        }
        if (type.equals("vehicle-map")) {
            settings.add(flag("follow", "APPEARANCE", "Map", "Initially follow vehicle", true));
            settings.add(
                flag(
                    "showMapDetails",
                    "APPEARANCE",
                    "Map",
                    "Show map controls and GPS/journey details",
                    true
                )
            );
            settings.add(
                flag(
                    "showMapNotices",
                    "APPEARANCE",
                    "Map",
                    "Visibility of the notice/footer block below the map (stale data and truncated trail notices). Separate from map controls/details and widget title; does not change recorded observations or source freshness",
                    true
                )
            );
            settings.add(
                new Setting(
                    "mapFields",
                    "FIELD_SET",
                    "APPEARANCE",
                    "Map tooltip fields",
                    "Map tooltip fields",
                    null,
                    List.of(),
                    100
                )
            );
        }
        if (List.of("vehicle-value", "vehicle-timeline").contains(type)) {
            settings.add(text("trueLabel", "State labels", "Label for true", 80));
            settings.add(text("falseLabel", "State labels", "Label for false", 80));
        }
        if (List.of("risk-list", "incident-list", "maintenance-agenda").contains(type)) {
            settings.add(
                flag(
                    "showRecordTime",
                    "APPEARANCE",
                    "Record details",
                    "Show record timestamps",
                    true
                )
            );
        }
        if (type.equals("maintenance-agenda")) {
            settings.add(
                flag(
                    "showMaintenanceFlags",
                    "APPEARANCE",
                    "Record details",
                    "Show alert and analytics exclusion columns",
                    true
                )
            );
        }
        if (type.equals("uptime-or-cycle")) {
            settings.add(
                flag(
                    "compactOperations",
                    "APPEARANCE",
                    "Summary",
                    "Compact confirmed operations summary",
                    false
                )
            );
        }
        if (type.equals("prediction-evidence")) {
            settings.add(
                new Setting(
                    "predictionSort",
                    "SELECT",
                    "APPEARANCE",
                    "Prediction details",
                    "Prediction order",
                    "RECENT",
                    List.of(
                        new Choice("RECENT", "Most recently calculated"),
                        new Choice("RISK_DESC", "Highest failure risk on this page")
                    ),
                    null
                )
            );
            settings.add(
                flag(
                    "showPredictionModel",
                    "APPEARANCE",
                    "Prediction details",
                    "Show model name and version",
                    false
                )
            );
            settings.add(
                flag(
                    "showPredictionTime",
                    "APPEARANCE",
                    "Prediction details",
                    "Show calculation time",
                    false
                )
            );
        }
        var visualTypes = new LinkedHashMap<String, List<String>>();
        for (String view : VisualizationCompatibility.choices(type)) {
            visualTypes.put(
                view,
                type.equals("vehicle-timeline") && view.equals("line")
                    ? List.of("number", "boolean")
                    : values
            );
        }
        return new Descriptor(
            1,
            dataKind,
            values,
            binding,
            VisualizationCompatibility.choices(type),
            Map.copyOf(visualTypes),
            List.copyOf(settings)
        );
    }

    /** Strict for new settings objects; saved legacy presentation retains unknown historical fields. */
    public static void validate(String type, JsonNode settings, boolean strict) {
        if (settings == null || settings.isNull()) return;
        if (!settings.isObject() || settings.size() > MAX_SETTINGS) throw invalid(
            "settings",
            "Settings must be a bounded object"
        );
        var known = forType(type).settings();
        settings.fields().forEachRemaining(entry -> {
            var setting = known
                .stream()
                .filter(item -> item.key().equals(entry.getKey()))
                .findFirst();
            if (setting.isEmpty()) {
                if (strict && !entry.getValue().isNull()) throw invalid(
                    entry.getKey(),
                    "Setting is not supported by this widget"
                );
                return;
            }
            JsonNode value = entry.getValue();
            if (value.isNull()) return;
            boolean valid = switch (setting.get().type()) {
                case "BOOLEAN", "AUTO_BOOLEAN" -> value.isBoolean();
                case "TEXT" -> value.isTextual() &&
                    value.asText().length() <= setting.get().maxLength();
                case "SELECT" -> value.isTextual() &&
                    setting
                        .get()
                        .choices()
                        .stream()
                        .anyMatch(choice -> choice.value().equals(value.asText()));
                case "FIELD_SET" -> value.isArray() &&
                    value.size() <= MAX_FIELDS &&
                    java.util.stream.StreamSupport.stream(value.spliterator(), false).allMatch(
                        field ->
                            field.isTextual() &&
                            field.asText().length() <= setting.get().maxLength() &&
                            field.asText().matches(FIELD_KEY_PATTERN)
                    );
                default -> false;
            };
            if (!valid) throw invalid(
                entry.getKey(),
                "Setting value does not match the widget capability"
            );
        });
    }

    private static Setting flag(
        String key,
        String section,
        String group,
        String label,
        boolean fallback
    ) {
        return new Setting(key, "BOOLEAN", section, group, label, fallback, List.of(), null);
    }

    private static Setting text(String key, String group, String label, int length) {
        return new Setting(key, "TEXT", "APPEARANCE", group, label, "", List.of(), length);
    }

    private static DashboardFailure invalid(String key, String message) {
        return new DashboardFailure("VALIDATION_ERROR", message, "settings." + key);
    }
}
