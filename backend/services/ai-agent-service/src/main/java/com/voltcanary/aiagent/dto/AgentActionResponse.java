package com.voltcanary.aiagent.dto;

import java.util.Map;
import java.util.UUID;

public record AgentActionResponse(
        UUID id,
        String tool,
        String title,
        String description,
        boolean requiresConfirmation,
        Map<String, Object> parameters) {
}
