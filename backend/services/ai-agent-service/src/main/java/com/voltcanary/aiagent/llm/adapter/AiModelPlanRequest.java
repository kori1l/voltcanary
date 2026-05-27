package com.voltcanary.aiagent.llm.adapter;

import java.util.Map;
import java.util.UUID;

public record AiModelPlanRequest(
        UUID accountId,
        UUID userId,
        String message,
        Map<String, Object> context,
        String systemPrompt,
        AiResolvedModel resolvedModel) {
}
