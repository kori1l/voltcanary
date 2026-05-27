package com.voltcanary.aiagent.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AgentPlan(
        UUID id,
        UUID userId,
        String userMessage,
        String summary,
        String explanation,
        List<String> safetyNotes,
        List<AgentAction> actions,
        Instant createdAt,
        String providerType,
        String providerName,
        String modelId,
        UUID providerId,
        UUID modelConfigId,
        boolean fallbackUsed) {
    public AgentPlan(
            UUID id,
            UUID userId,
            String userMessage,
            String summary,
            String explanation,
            List<String> safetyNotes,
            List<AgentAction> actions,
            Instant createdAt) {
        this(id, userId, userMessage, summary, explanation, safetyNotes, actions, createdAt, "rules", "Rules", "rule-based planner", null, null, true);
    }
}
