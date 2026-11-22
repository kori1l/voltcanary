package com.voltcanary.aiagent.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.aiagent.orchestration.context.ContextSources;
import com.voltcanary.aiagent.orchestration.contract.ProtocolValidator;
import com.voltcanary.aiagent.orchestration.plan.IntentPlanStore;
import com.voltcanary.aiagent.orchestration.plan.IntentPlans;
import com.voltcanary.aiagent.orchestration.plan.OrchestrationPorts.Session;
import com.voltcanary.aiagent.service.AuthorizedPlanningContext;
import com.voltcanary.aiagent.service.PlanStore;
import java.util.UUID;
import lombok.RequiredArgsConstructor;

/** Immutable reviewed commands are mandatory context for refinement, including a just-superseded review. */
@RequiredArgsConstructor
public final class ScopedReviewContext implements ContextSources.Reviews {
    private final PlanStore reviews;
    private final IntentPlanStore plans;
    private final AuthorizedPlanningContext authority;
    private final ProtocolValidator validator;
    private final ObjectMapper mapper;

    @Override
    public ContextSources.BaseReview read(IntentPlans.ModelInput input, Session session) {
        var submitted = validator.parse(plans.request(session.owner(), input.state().planId(), input.token().requestId()));
        String selected = submitted.path("selectedReviewId").asText(null);
        if (selected == null) selected = input.state().focus().reviewId();
        if (selected == null) return null;
        if (!session.owner().equals(authority.scope(session.user(), session.authorization()))) throw new IntentPlans.Rejected(403, "CONTEXT_SCOPE_CHANGED");
        var review = reviews.find(UUID.fromString(selected), session.owner());
        authority.revalidate(review.refs(), session.authorization());
        var data = mapper.createObjectNode().put("reviewId", selected).put("version", review.version()).put("digest", review.digest()).put("status", review.status());
        var actions = data.putArray("actions");
        for (var action : review.plan().actions()) {
            // Reviewed definitions are server-compiled and deeper than raw user input. Apply canonical bounds and the existing secret policy.
            var parameters = mapper.valueToTree(action.parameters());
            validator.parse(parameters.toString());
            var safe = com.voltcanary.aiagent.conversation.ConversationRedaction.value(parameters, com.fasterxml.jackson.databind.JsonNode.class, mapper);
            if (!safe.equals(parameters)) throw IntentPlans.reject("REVIEW_CONTEXT_CONTAINS_CREDENTIALS");
            var value = actions.addObject().put("actionId", action.id().toString()).put("tool", action.tool().name());
            value.set("parameters", mapper.valueToTree(action.parameters()));
            if (java.util.Set.of(com.voltcanary.aiagent.model.AgentTool.CREATE_DASHBOARD, com.voltcanary.aiagent.model.AgentTool.UPDATE_DASHBOARD_DRAFT).contains(action.tool()))
                value.set("currentWidgetInventory", com.voltcanary.aiagent.service.CurrentWidgetInventory.from(mapper, parameters));
        }
        validator.parse(data.toString());
        return new ContextSources.BaseReview(selected, review.digest(), data);
    }
}
