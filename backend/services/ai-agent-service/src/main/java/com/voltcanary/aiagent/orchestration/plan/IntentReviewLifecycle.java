package com.voltcanary.aiagent.orchestration.plan;

import static com.voltcanary.aiagent.orchestration.plan.IntentPlans.reject;

import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.Diagnostic;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.Focus;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.Goal;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.Manifest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Keeps immutable reviewed execution separate from mutable planning and server-owned success. */
public final class IntentReviewLifecycle {

    private final OrchestrationPorts.Receipts receipts;
    private final ConfirmedInputs inputs;

    public IntentReviewLifecycle(OrchestrationPorts.Receipts receipts) {
        this.receipts = receipts;
        this.inputs = new ConfirmedInputs(receipts);
    }

    public IntentPlans.State attach(
        IntentPlans.State state,
        String stepId,
        Manifest skill,
        OrchestrationPorts.Prepared prepared,
        OrchestrationPorts.Session session
    ) {
        var actual = receipts.inspect(prepared.review(), session);
        if (
            !actual.review().equals(prepared.review()) ||
            !actual.status().equals("READY") ||
            actual.review().actions().isEmpty()
        ) throw reject("REVIEW_NOT_READY");
        var keys = new HashSet<String>();
        for (var output : prepared.outputs()) {
            if (
                !output.reviewId().equals(actual.review().reviewId()) ||
                !actual.review().actions().contains(output.actionId()) ||
                !keys.add(output.key()) ||
                output.pointer() == null ||
                !output.pointer().startsWith("/") ||
                output.pointer().length() > 500
            ) throw reject("OUTPUT_RECEIPT_BINDING_INVALID");
            if (
                skill
                    .outputs()
                    .stream()
                    .noneMatch(
                        item -> item.key().equals(output.key()) && item.type().equals(output.type())
                    )
            ) throw reject("OUTPUT_CONTRACT_MISMATCH");
        }
        var reviews = new HashMap<>(state.reviews());
        reviews.put(stepId, actual.review());
        var outputs = new HashMap<>(state.outputs());
        outputs.put(stepId, prepared.outputs());
        var step = IntentPlans.step(
            state.step(stepId),
            "WAITING_CONFIRMATION",
            prepared.review().reviewId().toString(),
            null,
            null
        );
        var next = IntentPlans.replace(state, step, "WAITING_CONFIRMATION", null);
        return next.revise(
            next.revision(),
            next.status(),
            next.steps(),
            next.goals(),
            next.facts(),
            null,
            new Focus(
                state.focus().deviceRef(),
                state.focus().dashboardRef(),
                step.reviewId(),
                stepId,
                "ACTIVE_PLAN"
            ),
            next.skills(),
            reviews,
            outputs
        );
    }

    public IntentPlans.State collect(
        IntentPlans.State state,
        String stepId,
        OrchestrationPorts.Session session
    ) {
        var review = state.reviews().get(stepId);
        if (review == null) throw reject("REVIEW_BINDING_MISSING");
        var receipt = receipts.inspect(review, session);
        if (!receipt.review().equals(review)) throw reject("REVIEW_CHANGED");
        boolean complete =
            !review.actions().isEmpty() &&
            review
                .actions()
                .stream()
                .allMatch(
                    id ->
                        receipt.actions().containsKey(id) &&
                        receipt.actions().get(id).status().equals("SUCCESS")
                );
        boolean uncertain =
            Set.of("RUNNING", "UNKNOWN").contains(receipt.status()) ||
            review
                .actions()
                .stream()
                .anyMatch(
                    id ->
                        !receipt.actions().containsKey(id) ||
                        Set.of("UNKNOWN", "RUNNING").contains(receipt.actions().get(id).status())
                );
        String status = complete ? "SUCCEEDED" : uncertain ? "UNKNOWN" : "FAILED";
        var facts = new ArrayList<>(state.facts());
        String source = state
            .goals()
            .stream()
            .filter(goal -> state.step(stepId).goalIds().contains(goal.goalId()))
            .map(Goal::sourceMessageId)
            .findFirst()
            .orElseThrow(() -> reject("STEP_SOURCE_MISSING"));
        String device = state.focus().deviceRef();
        String dashboard = state.focus().dashboardRef();
        for (var output : state.outputs().getOrDefault(stepId, List.of())) {
            var action = receipt.actions().get(output.actionId());
            if (action == null || !action.status().equals("SUCCESS")) continue;
            String factId = state.planId() + ":" + stepId + ":" + output.key();
            if (facts.stream().noneMatch(item -> item.fact().factId().equals(factId))) {
                // Current resource access failure is distinct from a failed mutation receipt.
                // Leave the receipt for later explicit reconciliation; never execute again here.
                var confirmed = inputs.fact(output, factId, source, session);
                facts.add(confirmed);
                if ("device".equals(output.resourceKind())) device = factId;
                if ("dashboard".equals(output.resourceKind())) dashboard = factId;
            }
        }
        var old = state.step(stepId);
        var step = IntentPlans.step(
            old,
            status,
            old.reviewId(),
            review.reviewId().toString(),
            complete
                ? null
                : new Diagnostic(
                      "EXECUTION",
                      uncertain ? "RECEIPT_UNKNOWN" : "EXECUTION_FAILED",
                      "Проверьте сохранённый результат выполнения",
                      List.of(),
                      true
                  )
        );
        var next = IntentPlans.replace(state, step, "ACTIVE", null);
        var refreshed = IntentPlanGraph.refresh(next.steps());
        boolean allSucceeded = refreshed
            .stream()
            .filter(item -> !Set.of("SUPERSEDED", "CANCELLED").contains(item.status()))
            .allMatch(item -> item.status().equals("SUCCEEDED"));
        String planStatus = state.status().equals("CANCELLED")
            ? "CANCELLED"
            : allSucceeded
              ? "SUCCEEDED"
              : refreshed
                      .stream()
                      .anyMatch(item -> Set.of("FAILED", "UNKNOWN").contains(item.status()))
                ? "FAILED"
                : "ACTIVE";
        var goals = next
            .goals()
            .stream()
            .map(goal -> {
                var owners = refreshed
                    .stream()
                    .filter(
                        item ->
                            !item.status().equals("SUPERSEDED") &&
                            item.goalIds().contains(goal.goalId())
                    )
                    .toList();
                return goal.disposition().equals("PENDING") &&
                    !owners.isEmpty() &&
                    owners.stream().allMatch(item -> item.status().equals("SUCCEEDED"))
                    ? new Goal(goal.goalId(), goal.sourceMessageId(), goal.text(), "VERIFIED", null)
                    : goal;
            })
            .toList();
        return next.revise(
            next.revision(),
            planStatus,
            refreshed,
            goals,
            facts,
            null,
            new Focus(
                device,
                dashboard,
                step.reviewId(),
                stepId,
                complete ? "RECENT_RESULT" : "ACTIVE_PLAN"
            ),
            next.skills(),
            next.reviews(),
            next.outputs()
        );
    }

    public IntentPlans.State reconcile(
        IntentPlans.State state,
        String stepId,
        OrchestrationPorts.Session session
    ) {
        var step = state.step(stepId);
        if (!Set.of("EXECUTING", "UNKNOWN").contains(step.status())) throw reject(
            "RECONCILIATION_NOT_REQUIRED"
        );
        var before = receipts.inspect(state.reviews().get(stepId), session);
        // READY after an interrupted execution claim means no durable execution receipt: do not dispatch it.
        if (before.status().equals("READY")) return IntentPlans.replace(
            state,
            IntentPlans.step(
                step,
                "UNKNOWN",
                step.reviewId(),
                step.receiptId(),
                new Diagnostic(
                    "EXECUTION",
                    "EXECUTION_NOT_DISPATCHED_OR_UNKNOWN",
                    "Подтвердите новый review после проверки",
                    List.of(),
                    false
                )
            ),
            "FAILED",
            null
        );
        if (before.status().equals("UNKNOWN")) receipts.reconcile(
            state.reviews().get(stepId),
            session
        );
        return collect(state, stepId, session);
    }
}
