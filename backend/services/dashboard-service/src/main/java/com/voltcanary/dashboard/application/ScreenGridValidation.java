package com.voltcanary.dashboard.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.voltcanary.dashboard.domain.DashboardDefinition;
import com.voltcanary.dashboard.domain.DashboardFailure;
import java.util.Set;

/** Additive display settings; resource authorization is independent of the grid. */
final class ScreenGridValidation {
    private static final Set<String> BREAKPOINTS = Set.of("desktop", "tablet", "mobile");
    private static final Set<String> FIELDS = Set.of("columns", "rowHeight", "gap");

    void validate(JsonNode grid) {
        if (grid == null || grid.isNull()) return;
        require(grid.isObject(), "Grid must contain responsive profiles");
        grid.fields().forEachRemaining(entry -> {
            require(BREAKPOINTS.contains(entry.getKey()), "Unknown grid breakpoint");
            JsonNode profile = entry.getValue();
            require(profile.isObject() && profile.size() == 3, "Grid profile requires columns, rowHeight and gap");
            profile.fieldNames().forEachRemaining(field -> require(FIELDS.contains(field), "Unknown grid setting"));
            bounded(profile.path("columns"), 1, 48, "columns");
            bounded(profile.path("rowHeight"), 16, 160, "rowHeight");
            bounded(profile.path("gap"), 0, 32, "gap");
        });
    }

    int columns(DashboardDefinition definition, String widgetId, String breakpoint) {
        for (DashboardDefinition.Screen screen : definition.screens()) {
            if (screen.widgetIds().contains(widgetId) && screen.grid() != null) {
                JsonNode profile = screen.grid().path(breakpoint);
                if (profile.has("columns")) return profile.path("columns").asInt();
            }
        }
        return switch (breakpoint) {
            case "desktop" -> 12;
            case "tablet" -> 8; // Retain accepted legacy tablet coordinates.
            case "mobile" -> 1;
            default -> throw new DashboardFailure("VALIDATION_ERROR", "Unknown layout breakpoint", "layouts");
        };
    }

    private void bounded(JsonNode value, int min, int max, String field) {
        require(value.isIntegralNumber() && value.canConvertToInt()
                && value.asInt() >= min && value.asInt() <= max, "Grid " + field + " exceeds integer bounds");
    }

    private void require(boolean valid, String message) {
        if (!valid) throw new DashboardFailure("VALIDATION_ERROR", message, "screens.grid");
    }
}
