package com.voltcanary.aiagent.dto;

import java.util.UUID;

public record DashboardTurnMetadata(
    UUID requestId,
    UUID taskId,
    UUID branchId,
    Long expectedTaskRevision,
    UUID baseReviewId,
    boolean acceptedSuggestion
) {}
