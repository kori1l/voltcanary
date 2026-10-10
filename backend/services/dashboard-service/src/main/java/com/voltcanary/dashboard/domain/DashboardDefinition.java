package com.voltcanary.dashboard.domain;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** Versioned definition; extension payloads are preserved, never executed. */
public record DashboardDefinition(
        int schemaVersion,
        String name,
        String description,
        JsonNode defaultContext,
        List<JsonNode> sections,
        List<Widget> widgets,
        JsonNode layouts,
        List<Screen> screens) {

    public DashboardDefinition {
        screens = screens == null ? List.of() : List.copyOf(screens);
    }

    public DashboardDefinition(int schemaVersion, String name, String description, JsonNode defaultContext,
            List<JsonNode> sections, List<Widget> widgets, JsonNode layouts) {
        this(schemaVersion, name, description, defaultContext, sections, widgets, layouts, List.of());
    }

    public record Screen(String id, String title, List<String> widgetIds, JsonNode grid) {
        public Screen(String id, String title, List<String> widgetIds) {
            this(id, title, widgetIds, null);
        }
    }

    public record Widget(
            String id,
            String type,
            int typeVersion,
            String title,
            JsonNode binding,
            JsonNode query,
            JsonNode presentation,
            JsonNode navigation,
            JsonNode payload) {
    }
}
