package com.voltcanary.aiagent.dto;

import java.util.Map;
import java.util.UUID;

public record ActionExecutionResponse(
        UUID actionId,
        String tool,
        String status,
        Map<String, Object> result,
        String error) {
}
