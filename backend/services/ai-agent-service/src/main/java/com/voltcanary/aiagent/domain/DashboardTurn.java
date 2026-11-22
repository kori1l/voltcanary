package com.voltcanary.aiagent.domain;

import java.time.Instant;
import java.util.UUID;

public record DashboardTurn(
    UUID turnId,
    UUID requestId,
    UUID taskId,
    UUID branchId,
    UUID userId,
    UUID accountId,
    String payloadHash,
    UUID baseReviewId,
    long revision,
    String message,
    String status,
    String failureCategory,
    UUID reviewId,
    Instant leaseUntil,
    DashboardTaskState outcome
) {}
