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
        Instant createdAt) {
}
