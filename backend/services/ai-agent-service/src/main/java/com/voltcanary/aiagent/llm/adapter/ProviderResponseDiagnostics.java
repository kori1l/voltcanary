package com.voltcanary.aiagent.llm.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.voltcanary.aiagent.trace.AssistantTrace;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Public response metadata only; never persist provider bodies or hidden reasoning. */
final class ProviderResponseDiagnostics {
    private ProviderResponseDiagnostics() { }

    static void record(JsonNode response, int configuredLimit) {
        var details = new LinkedHashMap<String, Object>();
        details.put("configuredOutputLimit", configuredLimit);
        String status = response == null ? "NO_RESPONSE" : response.path("status").asText("UNKNOWN");
        details.put("providerStatus", Set.of("completed", "incomplete", "failed", "cancelled").contains(status) ? status : "UNKNOWN");
        if (response != null) {
            String reason = response.path("incomplete_details").path("reason").asText();
            if (Set.of("max_output_tokens", "content_filter").contains(reason)) details.put("incompleteReason", reason);
            var usage = response.path("usage");
            number(details, "inputTokens", usage.path("input_tokens"));
            number(details, "outputTokens", usage.path("output_tokens"));
            number(details, "cachedInputTokens", usage.path("input_tokens_details").path("cached_tokens"));
            number(details, "internalTokenCount", usage.path("output_tokens_details").path("reasoning_tokens"));
            int characters = response.path("output_text").asText("").length();
            if (characters == 0) for (var item : response.path("output")) {
                if (!"message".equals(item.path("type").asText())) continue;
                for (var content : item.path("content")) if ("output_text".equals(content.path("type").asText())) {
                    characters += content.path("text").asText("").length();
                }
            }
            details.put("finalTextCharacters", characters);
        }
        AssistantTrace.event("PROVIDER_USAGE", "OBSERVED", null, Map.copyOf(details));
    }

    private static void number(Map<String, Object> target, String key, JsonNode value) {
        if (value.isIntegralNumber() && value.canConvertToLong() && value.longValue() >= 0) target.put(key, value.longValue());
    }
}
