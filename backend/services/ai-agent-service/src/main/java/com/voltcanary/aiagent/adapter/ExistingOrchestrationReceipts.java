package com.voltcanary.aiagent.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.aiagent.orchestration.plan.IntentPlans;
import com.voltcanary.aiagent.orchestration.plan.OrchestrationPorts;
import com.voltcanary.aiagent.service.AiAgentOrchestrator;
import com.voltcanary.aiagent.service.AuthorizedPlanningContext;
import com.voltcanary.aiagent.service.PlanStore;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Reuses exact reviewed execution and action receipts, including its existing UNKNOWN reconciler. */
@Component
@RequiredArgsConstructor
public class ExistingOrchestrationReceipts implements OrchestrationPorts.Receipts {

    private final PlanStore plans;
    private final AiAgentOrchestrator execution;
    private final AuthorizedPlanningContext contexts;
    private final ObjectMapper mapper;

    @Override
    public IntentPlans.Receipt inspect(
        IntentPlans.Review review,
        OrchestrationPorts.Session session
    ) {
        checkSession(session);
        var stored = plans.find(review.reviewId(), session.owner());
        var actual = new IntentPlans.Review(
            stored.plan().id(),
            stored.version(),
            stored.digest(),
            stored
                .plan()
                .actions()
                .stream()
                .map(action -> action.id())
                .toList(),
            stored
                .plan()
                .actions()
                .stream()
                .anyMatch(action -> action.requiresConfirmation())
        );
        if (!actual.equals(review)) throw IntentPlans.conflict("REVIEW_CHANGED");
        contexts.revalidate(stored.refs(), session.authorization());
        var actions = new LinkedHashMap<java.util.UUID, IntentPlans.ActionReceipt>();
        for (var result : plans.actionResults(stored.plan())) {
            actions.put(
                result.actionId(),
                new IntentPlans.ActionReceipt(result.status(), mapper.valueToTree(result.result()))
            );
        }
        return new IntentPlans.Receipt(actual, stored.status(), Map.copyOf(actions));
    }

    @Override
    public IntentPlans.Receipt execute(
        IntentPlans.Review review,
        OrchestrationPorts.Session session
    ) {
        var before = inspect(review, session);
        if (!before.status().equals("READY")) return before;
        execution.execute(
            session.user(),
            review.reviewId(),
            true,
            session.authorization(),
            review.version(),
            review.digest()
        );
        return inspect(review, session);
    }

    @Override
    public IntentPlans.Receipt reconcile(
        IntentPlans.Review review,
        OrchestrationPorts.Session session
    ) {
        var before = inspect(review, session);
        if (before.status().equals("UNKNOWN")) {
            execution.execute(
                session.user(),
                review.reviewId(),
                true,
                session.authorization(),
                review.version(),
                review.digest()
            );
        }
        return inspect(review, session);
    }

    @Override
    public JsonNode output(IntentPlans.Output output, OrchestrationPorts.Session session) {
        checkSession(session);
        var stored = plans.find(output.reviewId(), session.owner());
        var receipt = plans
            .actionResults(stored.plan())
            .stream()
            .filter(item -> item.actionId().equals(output.actionId()))
            .findFirst()
            .orElseThrow(() -> IntentPlans.reject("RECEIPT_UNAVAILABLE"));
        if (!receipt.status().equals("SUCCESS")) throw IntentPlans.reject("RECEIPT_NOT_SUCCESSFUL");
        JsonNode result = mapper.valueToTree(receipt.result());
        JsonNode value = result.at(output.pointer());
        if (value.isMissingNode() || value.isNull()) throw IntentPlans.reject(
            "RECEIPT_OUTPUT_MISSING"
        );
        contexts.revalidate(stored.refs(), session.authorization());
        if (output.resourceKind() != null) {
            if (
                !List.of("device", "source", "dashboard", "alert", "location", "dataset").contains(
                    output.resourceKind()
                ) ||
                !value.isTextual()
            ) throw IntentPlans.reject("RESOURCE_KIND_INVALID");
            var summary = new LinkedHashMap<String, Object>();
            if (result.hasNonNull("deviceId")) summary.put(
                "deviceId",
                result.path("deviceId").asText()
            );
            if (
                output.resourceKind().equals("source") && !summary.containsKey("deviceId")
            ) throw IntentPlans.reject("SOURCE_OWNER_MISSING");
            contexts.revalidate(
                List.of(
                    new AuthorizedPlanningContext.ResourceRef(
                        output.key(),
                        output.resourceKind(),
                        value.asText(),
                        summary
                    )
                ),
                session.authorization()
            );
        }
        return value.deepCopy();
    }

    private void checkSession(OrchestrationPorts.Session session) {
        if (
            !contexts.scope(session.user(), session.authorization()).equals(session.owner())
        ) throw new IntentPlans.Rejected(403, "SESSION_SCOPE_CHANGED");
    }

    @Override
    public void invalidate(IntentPlans.Review review, OrchestrationPorts.Session session) {
        var actual = inspect(review, session);
        if (actual.status().equals("READY")) plans.invalidate(review.reviewId(), session.owner());
        else if (SetHolder.PERFORMED.contains(actual.status())) throw IntentPlans.conflict(
            "REVIEW_ALREADY_DISPATCHED"
        );
    }

    private static final class SetHolder {

        private static final java.util.Set<String> PERFORMED = java.util.Set.of(
            "RUNNING",
            "UNKNOWN",
            "SUCCESS",
            "PARTIAL_SUCCESS"
        );
    }
}
