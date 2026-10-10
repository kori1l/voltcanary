package com.voltcanary.dashboard.templates;

import com.voltcanary.dashboard.query.WidgetManifests;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Immutable task defaults. Resource slots contain symbols, never tenant IDs. */
@Component
public class DashboardTaskTemplates {
    public record Panel(String key, String type, String title, String slot,
            String metric, String unit, Map<String, Object> query, String drillDown) { }
    public record Template(String id, int version, String title, String task, String persona,
            List<String> segments, boolean available, String explanation,
            String period, int seriesHours, List<String> slots, List<Panel> panels) { }
    private final WidgetManifests manifests;
    public DashboardTaskTemplates(WidgetManifests manifests) { this.manifests = manifests; }

    public List<Template> catalog() {
        return List.of(
            template("shift", "Shift overview", "Start a shift and review current incidents", "Manager",
                List.of("Café", "Store", "Laundry", "Property"), "LAST_24_HOURS", 6,
                List.of(count(), incidents(), maintenance(), power(), quality())),
            template("my-devices", "My devices", "Check measurements and recorded problems", "Owner",
                List.of("Home", "Workshop", "IT"), "LAST_24_HOURS", 24,
                List.of(latest("power", "Latest power", "activePowerW", "W"), incidents(), power(), quality())),
            template("refrigeration", "Refrigeration", "Review recorded temperature and power separately", "Manager",
                List.of("Café", "Store", "Home"), "LAST_24_HOURS", 24,
                List.of(latest("temperature", "Latest temperature", "temperatureC", "C"),
                    panel("temperature-series", "time-series", "Temperature history", "primary", "temperatureC", "C", Map.of(), "/devices"),
                    power(), incidents(), risk(), maintenance())),
            template("technician", "Technician", "Review recorded risk, incidents and upcoming maintenance", "Technician",
                List.of("Service", "Property", "Workshop", "IT"), "LAST_24_HOURS", 24,
                List.of(risk(), incidents(), maintenance(), quality(), panel("note", "context-note", "Task context", null, null, null, Map.of(), "/devices"))),
            new Template("energy", 1, "Energy", "Compare verified energy periods", "Owner",
                List.of("Café", "Store", "Laundry", "Property", "Workshop", "IT", "Service", "Home"),
                manifests.find("energy-period", 1).map(item -> item.supported()).orElse(false),
                "Uses saved physical meters, covered energy and recorded equipment peaks. Tariff cost and confirmed cycles are separate capabilities.",
                "LAST_7_DAYS", 24, List.of("primary", "equipment"), List.of(
                    panel("energy", "energy-period", "Covered energy consumption", "equipment", null, null, Map.of("compare", Map.of("mode", "PREVIOUS")), "/devices"),
                    panel("peak", "peak-and-ranking", "Recorded peak and ranking", "equipment", null, null, Map.of(), "/devices"),
                    panel("cost", "estimated-cost", "Estimated cost requires a versioned tariff", "equipment", null, null, Map.of(), "/devices"), power(), quality())));
    }
    private Template template(String id, String title, String task, String persona, List<String> segments,
            String period, int hours, List<Panel> panels) {
        return new Template(id, 1, title, task, persona, segments, true,
            "Bind authorized equipment before preview. Missing measurements remain unavailable; persona labels do not grant rights.",
            period, hours, List.of("primary", "equipment", "location"), panels);
    }
    private Panel count() { return panel("fleet", "fleet-count", "Registered equipment and freshness", "equipment", null, null, Map.of(), "/devices"); }
    private Panel incidents() { return panel("incidents", "incident-list", "Current active incidents", "equipment", null, null, Map.of("incidentMode", "ACTIVE", "rows", 20), "/alerts"); }
    private Panel maintenance() { return panel("maintenance", "maintenance-agenda", "Next seven days of maintenance", "equipment", null, null, Map.of("time", Map.of("mode", "RELATIVE", "preset", "NEXT_7_DAYS", "timezone", "UTC"), "rows", 20), "/maintenance"); }
    private Panel quality() { return panel("quality", "quality-and-freshness", "Measurement quality and age", "equipment", null, null, Map.of(), "/devices"); }
    private Panel risk() { return panel("risk", "risk-list", "Recorded high or unknown risk", "equipment", null, null, Map.of("rows", 20), "/devices"); }
    private Panel power() { return panel("power-history", "time-series", "Measured power history", "primary", "activePowerW", "W", Map.of("reducer", "RAW"), "/devices"); }
    private Panel latest(String key, String title, String metric, String unit) { return panel(key, "latest-value", title, "primary", metric, unit, Map.of("reducer", "RAW"), "/devices"); }
    private Panel panel(String key, String type, String title, String slot, String metric, String unit, Map<String, Object> query, String drillDown) {
        return new Panel(key, type, title, slot, metric, unit, query, drillDown);
    }
}
