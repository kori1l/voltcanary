package com.voltcanary.aiagent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record ClarifyPlanRequest(@NotBlank @Size(max = 2000) String message, UUID requestId,
        UUID taskId, UUID branchId, Long expectedTaskRevision, UUID baseReviewId, Boolean acceptedSuggestion) {
    public ClarifyPlanRequest(String message) { this(message, null, null, null, null, null, null); }
    public DashboardTurnMetadata metadata() {
        return new DashboardTurnMetadata(requestId, taskId, branchId, expectedTaskRevision, baseReviewId,
                Boolean.TRUE.equals(acceptedSuggestion));
    }
}
