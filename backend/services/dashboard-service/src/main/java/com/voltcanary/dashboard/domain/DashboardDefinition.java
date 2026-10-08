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
        JsonNode layouts) {

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
