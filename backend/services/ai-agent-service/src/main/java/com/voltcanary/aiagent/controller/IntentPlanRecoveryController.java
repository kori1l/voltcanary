package com.voltcanary.aiagent.controller;

import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.Focus;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.PendingQuestion;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.PlanState;
import com.voltcanary.aiagent.orchestration.plan.IntentPlanStore;
import com.voltcanary.aiagent.orchestration.plan.IntentPlans;
import com.voltcanary.aiagent.security.JwtIdentityService;
import com.voltcanary.aiagent.service.AuthorizedPlanningContext;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only recovery. No generation, reconciliation, expiry writes or domain mutations. */
@RestController
@RequiredArgsConstructor
public class IntentPlanRecoveryController {

    public record Recovery(
        UUID planId,
        UUID conversationId,
        long revision,
        String status,
        PlanState plan,
        PendingQuestion pendingQuestion,
        Focus focus
    ) {}

    private final IntentPlanStore store;
    private final JwtIdentityService identity;
    private final AuthorizedPlanningContext contexts;

    @GetMapping("/ai-agent/orchestrations/{id}")
    public PlanState get(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @PathVariable("id") UUID id
    ) {
        var recovery = recovery(authorization, id);
        if (recovery.plan() == null) throw IntentPlans.conflict("INITIAL_PLAN_AWAITS_INPUT");
        return recovery.plan();
    }

    @GetMapping("/ai-agent/orchestrations/{id}/recovery")
    public Recovery recovery(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @PathVariable("id") UUID id
    ) {
        var state = store.read(scope(authorization), id);
        String status = state.expiresAt().isAfter(java.time.Instant.now())
            ? state.status()
            : "EXPIRED";
        if (status.equals("EXPIRED")) state = state.revise(
            state.revision(),
            status,
            state.steps(),
            state.goals(),
            state.facts(),
            state.pendingQuestion(),
            state.focus(),
            state.skills(),
            state.reviews(),
            state.outputs()
        );
        return new Recovery(
            state.planId(),
            state.conversationId(),
            state.revision(),
            status,
            state.wire(),
            state.pendingQuestion(),
            state.focus()
        );
    }

    @GetMapping("/ai-agent/conversations/{id}/orchestration-resources")
    public List<IntentPlans.ResourceEvent> resources(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @PathVariable("id") UUID id,
        @RequestParam(value = "after", defaultValue = "0") long after,
        @RequestParam(value = "limit", defaultValue = "50") int limit
    ) {
        return store.resources(scope(authorization), id, after, limit);
    }

    private AuthorizedPlanningContext.Scope scope(String authorization) {
        return contexts.scope(identity.parseBearerToken(authorization), authorization);
    }
}
