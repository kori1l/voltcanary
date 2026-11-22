package com.voltcanary.aiagent.orchestration.plan;

import com.voltcanary.aiagent.service.AuthorizedPlanningContext.Scope;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface IntentPlanStore {
    IntentPlans.Claim begin(
        Scope owner,
        IntentPlans.State seed,
        UUID requestId,
        long expectedRevision,
        String requestKind,
        String requestJson,
        String rawMessage,
        boolean interrupt
    );
    IntentPlans.State read(Scope owner, UUID planId);
    IntentPlans.State commit(
        IntentPlans.Token token,
        IntentPlans.State state,
        String reply,
        boolean terminal
    );
    Set<String> sourceMessages(Scope owner, UUID conversationId);
    List<IntentPlans.ResourceEvent> resources(
        Scope owner,
        UUID conversationId,
        long after,
        int limit
    );
    UUID conversation(Scope owner, UUID planId);
    IntentPlans.SourceMessage latestInput(Scope owner, UUID planId);
    String request(Scope owner, UUID planId, UUID requestId);

    default IntentPlans.Claim begin(
        Scope owner,
        IntentPlans.State seed,
        UUID requestId,
        long expectedRevision,
        String requestKind,
        String requestJson,
        String rawMessage,
        boolean interrupt,
        Long conversationRevision
    ) {
        return begin(
            owner,
            seed,
            requestId,
            expectedRevision,
            requestKind,
            requestJson,
            rawMessage,
            interrupt
        );
    }
}
