package com.voltcanary.aiagent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.voltcanary.aiagent.orchestration.plan.IntentPlans;
import com.voltcanary.aiagent.orchestration.routing.ModelProposalRejected;
import java.util.Set;

/** Structural routing checks only; interpreting the user's request remains the planner's responsibility. */
public final class PrimaryPlannerChecks {
    private PrimaryPlannerChecks() { }

    public static void validate(IntentPlans.ModelInput input, JsonNode proposal) {
        if (proposal.path("decision").asText().equals("ASK")) {
            for (var field : proposal.at("/question/missingFields")) {
                if (!Set.of("intent", "target").contains(field.asText())) {
                    reject(proposal, "PLANNER_QUESTION_OUTSIDE_ROUTING_SCOPE",
                            "Ask only intent/target routing questions. Select the known workflow; its skill handles domain parameters using the exact message and history.");
                }
            }
        }
        if (!proposal.path("decision").asText().equals("CONTINUE")) return;
        var target = input.state().steps().stream()
                .filter(step -> step.stepId().equals(proposal.path("targetStepId").asText())).findFirst();
        if (target.isEmpty() || !Set.of("READY", "WAITING_INPUT", "WAITING_CONFIRMATION").contains(target.get().status())) {
            reject(proposal, "PLANNER_CONTINUE_TARGET_INVALID", "Use an actual continuable step; replace a failed step through AMEND.");
        }
        var step = target.orElseThrow();
        if (step.status().equals("WAITING_CONFIRMATION") && DashboardSkillContract.supports(step.intentId())) {
            reject(proposal, "PLANNER_ACCEPTED_DASHBOARD_REQUIRES_AMEND",
                    "This dashboard already has a prepared review. A new change must AMEND to the requiredIntent in its dashboard-review choice, preserving pending goals. Do not CONTINUE its old generation step. Execution needs the separate confirmation control.");
        }
    }

    private static void reject(JsonNode proposal, String code, String instruction) {
        throw new ModelProposalRejected(proposal, code + ": " + instruction);
    }
}
