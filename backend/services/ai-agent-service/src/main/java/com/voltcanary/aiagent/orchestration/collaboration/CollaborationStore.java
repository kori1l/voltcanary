package com.voltcanary.aiagent.orchestration.collaboration;

import com.voltcanary.aiagent.orchestration.plan.IntentPlans;
import com.voltcanary.aiagent.service.AuthorizedPlanningContext.Scope;
import java.time.Instant;
import java.util.UUID;

/** Plan-owned JSON with CAS and an append-only change journal. Mutations must perform no external I/O. */
public interface CollaborationStore {
    record State(
        long revision,
        Requirements.Snapshot requirements,
        Consultations.Tree consultations
    ) {
        public State {
            if (
                revision < 0 ||
                revision > Requirements.MAX_REVISION ||
                requirements == null ||
                consultations == null ||
                !requirements.planId().equals(consultations.planId())
            ) throw Requirements.invalid("INVALID_COLLABORATION_STATE");
        }
    }

    @FunctionalInterface
    interface Mutation {
        State apply(State current, Instant databaseTime);
    }

    State read(Scope owner, UUID planId);

    /** The root request remains the authority; an old worker cannot write after root cancellation or replacement. */
    State update(
        IntentPlans.Token turn,
        long expectedRevision,
        String requestKey,
        String requestDigest,
        Mutation mutation
    );
}
