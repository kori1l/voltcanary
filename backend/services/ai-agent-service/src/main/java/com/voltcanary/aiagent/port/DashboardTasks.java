package com.voltcanary.aiagent.port;

import com.voltcanary.aiagent.domain.DashboardTaskState;
import com.voltcanary.aiagent.domain.DashboardTurn;
import java.util.Optional;
import java.util.UUID;

/** Transactions end before any provider, catalogue or resource API call begins. */
public interface DashboardTasks {
    Optional<DashboardTurn> request(UUID userId, UUID accountId, UUID requestId);
    DashboardTaskState find(UUID userId, UUID accountId, UUID taskId, UUID branchId);
    Optional<DashboardTurn> review(UUID userId, UUID accountId, UUID reviewId);
    DashboardTaskState accept(DashboardTaskState state, DashboardTurn turn, Long expectedRevision, boolean newBranch);
    void checkpoint(DashboardTaskState state);
    void finish(DashboardTaskState state, DashboardTurn turn);
}
