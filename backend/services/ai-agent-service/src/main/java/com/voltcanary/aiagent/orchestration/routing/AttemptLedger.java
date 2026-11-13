package com.voltcanary.aiagent.orchestration.routing;

import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.RoutingPolicy;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.Usage;
import com.voltcanary.aiagent.service.AuthorizedPlanningContext.Scope;
import java.util.UUID;

/** One durable owner of every planner, step, continuation, repair and format-correction call. */
public interface AttemptLedger {
    record Turn(Scope owner, UUID conversationId, UUID planId, UUID turnId) { }
    record Attempt(UUID callId, String phase, String stepId, String requestDigest,
            String policyId, String policyVersion, String skillVersion, String schemaVersion, String contextVersion,
            ModelRouting.Route route, String purpose) {
        public Attempt(UUID callId, String phase, String stepId, String requestDigest, String policyId, String policyVersion,
                String skillVersion, String schemaVersion, String contextVersion, ModelRouting.Route route) {
            this(callId, phase, stepId, requestDigest, policyId, policyVersion, skillVersion, schemaVersion, contextVersion, route,
                    phase.equals("REPAIR") ? "DOMAIN_RECOVERY" : "PRIMARY");
        }
    }
    record Receipt(boolean newlyReserved, String status, String responseJson) { }

    void open(Turn turn, RoutingPolicy policy);
    long remainingCost(Turn turn);
    long remainingTimeMillis(Turn turn);
    Receipt reserve(Turn turn, Attempt attempt);
    void finish(Turn turn, UUID callId, String status, Usage usage, String validatedResponseJson);
    void cancel(Turn turn);
}
