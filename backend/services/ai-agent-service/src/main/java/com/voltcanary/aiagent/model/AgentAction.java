package com.voltcanary.aiagent.model;

import java.util.Map;
import java.util.UUID;

public record AgentAction(
        UUID id,
        AgentTool tool,
        String title,
        String description,
        boolean requiresConfirmation,
        Map<String, Object> parameters) {
}
