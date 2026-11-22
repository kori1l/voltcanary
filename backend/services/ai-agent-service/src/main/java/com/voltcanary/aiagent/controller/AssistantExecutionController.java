package com.voltcanary.aiagent.controller;

import com.voltcanary.aiagent.dto.ExecutePlanRequest;
import com.voltcanary.aiagent.dto.ExecutePlanResponse;
import com.voltcanary.aiagent.port.AssistantTraces;
import com.voltcanary.aiagent.security.JwtIdentityService;
import com.voltcanary.aiagent.service.AiAgentOrchestrator;
import com.voltcanary.aiagent.service.AuthorizedPlanningContext;
import com.voltcanary.aiagent.trace.AssistantTrace;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/ai-agent/plans")
@RequiredArgsConstructor
public class AssistantExecutionController {

    private final JwtIdentityService identity;
    private final AuthorizedPlanningContext contexts;
    private final AssistantTraces traces;
    private final AiAgentOrchestrator orchestrator;
    private final com.voltcanary.aiagent.service.PrimaryAssistant primary;

    @PostMapping("/{planId}/execute")
    public ExecutePlanResponse execute(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @PathVariable("planId") UUID planId,
        @Valid @RequestBody ExecutePlanRequest request
    ) {
        var user = identity.parseBearerToken(authorization);
        if (primary.mapped(user, planId, authorization)) return primary.execute(
            user,
            planId,
            request.confirmed(),
            request.expectedVersion(),
            request.payloadDigest(),
            authorization
        );
        var correlation = traces.plan(contexts.scope(user, authorization), planId);
        try (
            var session = correlation.map(value -> AssistantTrace.open(traces, value)).orElse(null);
            var execution = AssistantTrace.start("EXECUTION", Map.of("reviewId", planId))
        ) {
            var result = orchestrator.execute(
                user,
                planId,
                request.confirmed(),
                authorization,
                request.expectedVersion(),
                request.payloadDigest()
            );
            if ("SUCCESS".equals(result.status())) execution.success(
                Map.of("executionStatus", result.status())
            );
            else execution.failure(
                "EXECUTION_" + result.status(),
                Map.of("executionStatus", result.status())
            );
            return result;
        }
    }
}
