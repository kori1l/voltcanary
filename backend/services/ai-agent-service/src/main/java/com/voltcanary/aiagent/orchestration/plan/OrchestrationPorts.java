package com.voltcanary.aiagent.orchestration.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.Command;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.Manifest;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.PlannerResponse;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.StepResponse;
import com.voltcanary.aiagent.security.AuthenticatedUser;
import com.voltcanary.aiagent.service.AuthorizedPlanningContext.Scope;
import java.util.List;
import java.util.UUID;

public final class OrchestrationPorts {
    private OrchestrationPorts() { }
    /** Ephemeral checked-session authority. Never serialized into plan/model/turn state. */
    public record Session(AuthenticatedUser user, Scope owner, String authorization) { }
    public interface Models {
        default boolean automaticCorrections() { return true; }
        PlannerResponse plan(IntentPlans.ModelInput input, Session session, UUID callId);
        StepResponse step(IntentPlans.ModelInput input, Session session, UUID callId);
        default StepResponse repair(IntentPlans.ModelInput input, Session session, UUID callId,
                com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.Diagnostic diagnostic, Command rejected) {
            throw IntentPlans.reject("REPAIR_UNAVAILABLE");
        }
    }
    @FunctionalInterface
    public interface Consultation {
        boolean run(IntentPlans.ModelInput input, String stepId, Session session, boolean nameRequested);
    }
    public record Prepared(IntentPlans.Review review, List<IntentPlans.Output> outputs) {
        public Prepared { outputs = List.copyOf(outputs); }
    }
    @FunctionalInterface
    public interface CommandReviews {
        Prepared prepare(IntentPlans.ModelInput input, Manifest skill, Command command, Session session);
    }
    public interface Receipts {
        IntentPlans.Receipt inspect(IntentPlans.Review review, Session session);
        IntentPlans.Receipt execute(IntentPlans.Review review, Session session);
        IntentPlans.Receipt reconcile(IntentPlans.Review review, Session session);
        JsonNode output(IntentPlans.Output output, Session session);
        void invalidate(IntentPlans.Review review, Session session);
    }
}
