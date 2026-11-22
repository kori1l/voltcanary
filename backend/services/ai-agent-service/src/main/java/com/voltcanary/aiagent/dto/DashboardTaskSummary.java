package com.voltcanary.aiagent.dto;

import com.voltcanary.aiagent.domain.DashboardTaskState;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Owner-visible working values only; no model context, opaque refs or credentials. */
public record DashboardTaskSummary(
    UUID taskId,
    UUID branchId,
    long revision,
    String kind,
    String originalRequest,
    String name,
    UUID lastAcceptedReviewId,
    String status,
    String failureCategory,
    Instant expiresAt,
    List<DashboardTaskState.PendingChange> pending,
    String selectionStatus
) {
    public static DashboardTaskSummary of(DashboardTaskState state) {
        return new DashboardTaskSummary(
            state.taskId(),
            state.branchId(),
            state.revision(),
            "DASHBOARD",
            state.originalRequest(),
            state.name() == null ? null : state.name().value(),
            state.lastAcceptedReviewId(),
            state.status(),
            state.failureCategory(),
            state.expiresAt(),
            state.pending(),
            "UNVERIFIED"
        );
    }

    public DashboardTaskSummary withSelectionStatus(String selectionStatus) {
        return new DashboardTaskSummary(
            taskId,
            branchId,
            revision,
            kind,
            originalRequest,
            name,
            lastAcceptedReviewId,
            status,
            failureCategory,
            expiresAt,
            pending,
            selectionStatus
        );
    }
}
