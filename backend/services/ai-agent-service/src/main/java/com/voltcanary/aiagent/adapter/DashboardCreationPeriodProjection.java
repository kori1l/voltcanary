package com.voltcanary.aiagent.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voltcanary.aiagent.orchestration.routing.ModelProposalRejected;
import java.util.HashSet;

/** Explicit model choice on the wire; existing reviewed operation payloads never change shape. */
final class DashboardCreationPeriodProjection {
    static final String GUIDANCE = "Creation PREPARED.command.payload.dashboardPeriod explicitly sets the WHOLE dashboard period. "
            + "Use an explicitly requested global period, or " + com.voltcanary.aiagent.service.DashboardOperationProtocol.DEFAULT_PERIOD + " when none is specified. "
            + "show_history.period overrides only that chart; it cannot set the dashboard default. "
            + "The server converts dashboardPeriod to the canonical global set_period operation before creating widgets.";
    private final ObjectMapper mapper;
    private final JsonNode periods;
    private final JsonNode schema;

    static boolean supports(JsonNode schema) {
        return schema.at("/$defs/command/properties/payload/properties/operations/items/anyOf").isArray();
    }

    DashboardCreationPeriodProjection(ObjectMapper mapper, JsonNode original) {
        this.mapper = mapper;
        var projected = original.<ObjectNode>deepCopy();
        var payload = (ObjectNode) projected.at("/$defs/command/properties/payload");
        JsonNode periodSchema = null;
        for (var operation : payload.at("/properties/operations/items/anyOf"))
            if (operation.at("/properties/op/enum/0").asText().equals("set_period"))
                periodSchema = operation.at("/properties/period").deepCopy();
        if (periodSchema == null) throw new IllegalArgumentException("CREATION_PERIOD_SCHEMA_MISSING");
        periods = periodSchema.path("enum").deepCopy();
        ((ObjectNode) periodSchema).put("description", GUIDANCE);
        ((ObjectNode) payload.path("properties")).set("dashboardPeriod", periodSchema);
        payload.withArray("required").add("dashboardPeriod");
        ((ObjectNode) payload.at("/properties/operations")).put("maxItems", 23);
        schema = projected;
    }

    JsonNode schema() { return schema.deepCopy(); }

    JsonNode correction(JsonNode rejected) {
        if (!rejected.path("outcome").asText().equals("PREPARED")
                || !rejected.at("/command/payload").isObject() || rejected.at("/command/payload").has("dashboardPeriod"))
            return rejected.deepCopy();
        var result = rejected.<ObjectNode>deepCopy();
        var payload = (ObjectNode) result.at("/command/payload");
        var operations = mapper.createArrayNode();
        JsonNode globalPeriod = null;
        for (var operation : payload.path("operations")) {
            if (!global(operation)) {
                operations.add(operation.deepCopy());
                continue;
            }
            var period = operation.path("period");
            if (!period.isTextual() || globalPeriod != null && !globalPeriod.equals(period)) return rejected.deepCopy();
            globalPeriod = period;
        }
        if (globalPeriod != null) payload.set("dashboardPeriod", globalPeriod.deepCopy());
        payload.set("operations", operations);
        return result;
    }

    JsonNode normalize(JsonNode proposal) {
        if (!proposal.path("outcome").asText().equals("PREPARED")) return proposal;
        var period = proposal.at("/command/payload/dashboardPeriod");
        boolean allowed = false;
        for (var candidate : periods) allowed |= candidate.equals(period);
        if (!allowed) throw new ModelProposalRejected(proposal, "Creation requires command.payload.dashboardPeriod from its enum; chart period is separate.");
        var result = proposal.<ObjectNode>deepCopy();
        var payload = (ObjectNode) result.at("/command/payload");
        var operations = mapper.createArrayNode();
        var ids = new HashSet<String>();
        for (var operation : payload.path("operations")) ids.add(operation.path("id").asText());
        String id = "dashboard_period";
        for (int suffix = 1; ids.contains(id); suffix++) id = "dashboard_period_" + suffix;
        operations.addObject().put("op", "set_period").put("id", id).putNull("targets").set("period", period.deepCopy());
        for (var operation : payload.path("operations")) {
            boolean global = global(operation);
            if (global && !operation.path("period").equals(period))
                throw new ModelProposalRejected(proposal, "dashboardPeriod conflicts with a global set_period; choose one whole-dashboard period.");
            if (!global) operations.add(operation.deepCopy());
        }
        payload.remove("dashboardPeriod");
        payload.set("operations", operations);
        return result;
    }

    private static boolean global(JsonNode operation) {
        var targets = operation.path("targets");
        return operation.path("op").asText().equals("set_period")
                && (targets.isMissingNode() || targets.isNull() || targets.isArray() && targets.isEmpty());
    }
}
