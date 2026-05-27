package com.voltcanary.aiagent.dto;

import java.util.List;
import java.util.UUID;

public record PlanResponse(
        UUID planId,
        String summary,
        String explanation,
        boolean confirmationRequired,
        List<String> safetyNotes,
        List<AgentActionResponse> actions,
        String providerType,
        String providerName,
        String modelId,
        UUID providerId,
        UUID modelConfigId,
        boolean fallbackUsed) {
}
