package com.voltcanary.aiagent.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import java.util.UUID;

public record PlanRequest(
    @NotBlank String message,
    Map<String, Object> context,
    UUID providerId,
    UUID modelConfigId,
    UUID requestId,
    UUID taskId,
    UUID branchId,
    Long expectedTaskRevision,
    UUID baseReviewId
) {
    public PlanRequest(
        String message,
        Map<String, Object> context,
        UUID providerId,
        UUID modelConfigId
    ) {
        this(message, context, providerId, modelConfigId, null, null, null, null, null);
    }

    public DashboardTurnMetadata metadata() {
        return new DashboardTurnMetadata(
            requestId,
            taskId,
            branchId,
            expectedTaskRevision,
            baseReviewId,
            false
        );
    }
}
