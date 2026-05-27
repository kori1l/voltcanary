package com.voltcanary.aiagent.dto;

import java.util.List;
import java.util.UUID;

public record ExecutePlanResponse(
        UUID planId,
        String status,
        List<ActionExecutionResponse> actions) {
}
