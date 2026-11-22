package com.voltcanary.dashboard.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voltcanary.dashboard.domain.DashboardFailure;
import java.util.UUID;

public record DashboardRuleMeasure(UUID epoch, UUID measure) {
    public static final String TYPE = "rule-window-result";

    public static DashboardRuleMeasure parse(JsonNode query) {
        if (query == null || !query.isObject() || query.size() != 2) throw invalid();
        try {
            return new DashboardRuleMeasure(uuid(query.path("epoch")), uuid(query.path("measure")));
        } catch (IllegalArgumentException failure) {
            throw invalid();
        }
    }

    public void project(ObjectNode output, JsonNode response) {
        if (
            response == null ||
            !epoch.toString().equals(response.path("epoch").asText()) ||
            !measure.toString().equals(response.path("measure").asText()) ||
            !response.path("status").isTextual() ||
            !response.path("from").isTextual() ||
            !response.path("until").isTextual() ||
            !response.path("evidence").isArray() ||
            response.path("evidence").size() > 100
        ) {
            throw new DashboardFailure(
                "INVALID_READ_RESPONSE",
                "Analytics returned an unexpected measure"
            );
        }
        output.set("ruleWindowResult", response);
    }

    private static UUID uuid(JsonNode value) {
        var text = value.asText();
        var id = UUID.fromString(text);
        if (!id.toString().equals(text)) throw invalid();
        return id;
    }

    private static DashboardFailure invalid() {
        return new DashboardFailure("VALIDATION_ERROR", "Select a deployment and a saved measure");
    }
}
