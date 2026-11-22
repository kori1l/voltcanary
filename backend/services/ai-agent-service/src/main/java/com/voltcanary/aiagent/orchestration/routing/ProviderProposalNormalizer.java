package com.voltcanary.aiagent.orchestration.routing;

import com.fasterxml.jackson.databind.JsonNode;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.Usage;
import com.voltcanary.aiagent.orchestration.contract.ProtocolValidator;
import lombok.RequiredArgsConstructor;

/** Provider envelopes are transport data. All paths still enter canonical + registered validation. */
@RequiredArgsConstructor
public final class ProviderProposalNormalizer {

    private final ProtocolValidator validator;

    public BudgetedModelCalls.Reply openAi(JsonNode envelope, String mode, Usage usage) {
        var choices = envelope.path("choices");
        if (choices.size() != 1) throw new ModelRouting.Stop("PROVIDER_CHOICE_INVALID");
        var choice = choices.get(0);
        String stop = choice.path("finish_reason").asText();
        if (!java.util.Set.of("stop", "tool_calls").contains(stop)) throw new ModelRouting.Stop(
            "PROVIDER_INCOMPLETE"
        );
        var message = choice.path("message");
        if (message.hasNonNull("refusal")) throw new ModelRouting.Stop("PROVIDER_REFUSAL");
        if (mode.equals("NATIVE_TOOLS")) {
            var tools = message.path("tool_calls");
            if (
                !stop.equals("tool_calls") ||
                tools.size() != 1 ||
                !tools.get(0).at("/function/name").asText().equals("assistant_proposal")
            ) {
                throw new ModelRouting.Stop("PROVIDER_TOOL_INVALID");
            }
            return normalize(mode, tools.get(0).at("/function/arguments"), usage);
        }
        if (
            !stop.equals("stop") || !message.path("content").isTextual()
        ) throw new ModelRouting.Stop("PROVIDER_OUTPUT_INVALID");
        return normalize(mode, message.path("content"), usage);
    }

    public BudgetedModelCalls.Reply anthropic(JsonNode envelope, String mode, Usage usage) {
        var content = envelope.path("content");
        String stop = envelope.path("stop_reason").asText();
        if (content.size() != 1) throw new ModelRouting.Stop("PROVIDER_OUTPUT_INVALID");
        var part = content.get(0);
        if (mode.equals("NATIVE_TOOLS")) {
            if (
                !stop.equals("tool_use") ||
                !part.path("type").asText().equals("tool_use") ||
                !part.path("name").asText().equals("assistant_proposal")
            ) throw new ModelRouting.Stop("PROVIDER_TOOL_INVALID");
            return normalize(mode, part.path("input"), usage);
        }
        if (
            !stop.equals("end_turn") || !part.path("type").asText().equals("text")
        ) throw new ModelRouting.Stop("PROVIDER_INCOMPLETE");
        return normalize(mode, part.path("text"), usage);
    }

    public BudgetedModelCalls.Reply gemini(JsonNode envelope, String mode, Usage usage) {
        var candidates = envelope.path("candidates");
        if (
            candidates.size() != 1 ||
            !candidates.get(0).path("finishReason").asText().equals("STOP")
        ) throw new ModelRouting.Stop("PROVIDER_INCOMPLETE");
        var parts = candidates.get(0).at("/content/parts");
        if (parts.size() != 1) throw new ModelRouting.Stop("PROVIDER_OUTPUT_INVALID");
        if (mode.equals("NATIVE_TOOLS")) {
            var call = parts.get(0).path("functionCall");
            if (
                !call.path("name").asText().equals("assistant_proposal")
            ) throw new ModelRouting.Stop("PROVIDER_TOOL_INVALID");
            return normalize(mode, call.path("args"), usage);
        }
        if (!parts.get(0).path("text").isTextual()) throw new ModelRouting.Stop(
            "PROVIDER_OUTPUT_INVALID"
        );
        return normalize(mode, parts.get(0).path("text"), usage);
    }

    public BudgetedModelCalls.Reply normalize(String mode, JsonNode proposal, Usage usage) {
        if (
            !java.util.Set.of("NATIVE_TOOLS", "STRICT_JSON", "JSON_TEXT").contains(mode)
        ) throw new ModelRouting.Stop("OUTPUT_MODE_UNSUPPORTED");
        String json = proposal.isTextual() ? proposal.asText() : proposal.toString();
        // No extraction from prose, markdown cleanup, format correction or hidden retry.
        validator.parse(json);
        return new BudgetedModelCalls.Reply(mode, json, usage);
    }
}
