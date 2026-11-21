package com.voltcanary.aiagent.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.aiagent.adapter.springai.DashboardProposalTools;
import com.voltcanary.aiagent.orchestration.contract.SkillRegistry;
import com.voltcanary.aiagent.orchestration.routing.ModelRouting;
import java.util.UUID;

/** Decodes the exact reserved wire body without constructing an SDK client. */
final class PreparedExchangeFactory {
    record Exchange(String digest, String instructions, String input, String schema, String tool, UUID planId) {
        boolean nativeTools() { return tool != null; }
    }

    private final ObjectMapper mapper;

    PreparedExchangeFactory(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    Exchange prepare(String body, String mode) throws com.fasterxml.jackson.core.JsonProcessingException {
        var envelope = mapper.readTree(body);
        var instructions = envelope.path("instructions");
        var input = envelope.path("input");
        if (!instructions.isTextual() || !input.isTextual()) throw invalid();
        var payload = mapper.readTree(input.textValue());
        if (payload == null || !payload.isObject()) throw invalid();
        UUID planId = payload.has("planId") ? UUID.fromString(payload.path("planId").asText()) : null;
        JsonNode schema = null;
        String tool = null;
        switch (mode) {
            case "JSON_TEXT" -> {
                if (envelope.has("tools") || envelope.has("text")) throw invalid();
            }
            case "STRICT_JSON" -> {
                if (envelope.has("tools")) throw invalid();
                schema = envelope.at("/text/format/schema");
            }
            case "NATIVE_TOOLS" -> {
                if (envelope.has("text") || !envelope.path("tools").isArray() || envelope.path("tools").size() != 1
                        || !envelope.path("parallel_tool_calls").isBoolean() || envelope.path("parallel_tool_calls").booleanValue()) {
                    throw invalid();
                }
                tool = envelope.at("/tools/0/name").asText();
                if (!tool.equals(envelope.at("/tool_choice/name").asText())) throw invalid();
                DashboardProposalTools.description(tool);
                schema = envelope.at("/tools/0/parameters");
            }
            default -> throw invalid();
        }
        if (schema != null && !schema.isObject()) throw invalid();
        return new Exchange(SkillRegistry.digest(body), instructions.textValue(), input.textValue(),
                schema == null ? null : schema.toString(), tool, planId);
    }

    private static ModelRouting.Stop invalid() {
        return new ModelRouting.Stop("PREPARED_EXCHANGE_INVALID");
    }
}
