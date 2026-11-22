package com.voltcanary.aiagent.dto;

import java.time.Instant;
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
    boolean fallbackUsed,
    int version,
    String payloadDigest,
    Instant expiresAt,
    String planningStatus,
    List<String> questions,
    String executionStatus,
    ExecutePlanResponse execution,
    com.voltcanary.aiagent.model.PlanningQuestion clarification,
    List<UUID> previousReviewIds,
    DashboardTaskSummary task,
    UUID restoredFromReviewId
) {
    public PlanResponse withTask(DashboardTaskSummary summary) {
        return new PlanResponse(
            planId,
            this.summary,
            explanation,
            confirmationRequired,
            safetyNotes,
            actions,
            providerType,
            providerName,
            modelId,
            providerId,
            modelConfigId,
            fallbackUsed,
            version,
            payloadDigest,
            expiresAt,
            planningStatus,
            questions,
            executionStatus,
            execution,
            clarification,
            previousReviewIds,
            summary,
            restoredFromReviewId
        );
    }
}
