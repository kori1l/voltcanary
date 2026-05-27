package com.voltcanary.aiagent.service;

import com.voltcanary.aiagent.audit.AuditLogger;
import com.voltcanary.aiagent.dto.ActionExecutionResponse;
import com.voltcanary.aiagent.dto.AgentActionResponse;
import com.voltcanary.aiagent.dto.ExecutePlanResponse;
import com.voltcanary.aiagent.dto.PlanResponse;
import com.voltcanary.aiagent.model.AgentAction;
import com.voltcanary.aiagent.model.AgentPlan;
import com.voltcanary.aiagent.security.AuthenticatedUser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AiAgentOrchestrator {

    private final ActionPlanner planner;
    private final PlanStore planStore;
    private final ToolExecutor toolExecutor;
    private final AuditLogger auditLogger;

    public AiAgentOrchestrator(ActionPlanner planner, PlanStore planStore, ToolExecutor toolExecutor, AuditLogger auditLogger) {
        this.planner = planner;
        this.planStore = planStore;
        this.toolExecutor = toolExecutor;
        this.auditLogger = auditLogger;
    }

    public PlanResponse createPlan(AuthenticatedUser user, String message, Map<String, Object> context) {
        AgentPlan plan = planner.plan(user, message, context);
        planStore.save(plan);
        auditLogger.record(user, "PLAN_CREATED", plan.id(), null, Map.of("actionCount", plan.actions().size()));
        return toResponse(plan);
    }

    public ExecutePlanResponse execute(AuthenticatedUser user, UUID planId, boolean confirmed, String authorization) {
        AgentPlan plan = planStore.find(planId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Plan not found"));
        if (!plan.userId().equals(user.userId())) {
            auditLogger.record(user, "PLAN_ACCESS_DENIED", planId, null, Map.of("planOwner", plan.userId()));
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Plan belongs to a different user");
        }
        boolean needsConfirmation = plan.actions().stream().anyMatch(AgentAction::requiresConfirmation);
        if (needsConfirmation && !confirmed) {
            auditLogger.record(user, "EXECUTION_REQUIRES_CONFIRMATION", plan.id(), null, Map.of("confirmed", false));
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Confirmation is required for mutating actions");
        }

        Map<String, Object> outputs = new LinkedHashMap<>();
        List<ActionExecutionResponse> results = new ArrayList<>();
        auditLogger.record(user, "PLAN_EXECUTION_STARTED", plan.id(), null, Map.of("confirmed", confirmed));
        for (AgentAction action : plan.actions()) {
            auditLogger.record(user, "ACTION_EXECUTION_STARTED", plan.id(), action.id(), Map.of("tool", action.tool().name()));
            ActionExecutionResponse result = toolExecutor.execute(action, authorization, user.userId(), outputs);
            results.add(result);
            Map<String, Object> auditDetails = new LinkedHashMap<>();
            auditDetails.put("tool", action.tool().name());
            auditDetails.put("error", result.error());
            auditLogger.record(user, "ACTION_EXECUTION_" + result.status(), plan.id(), action.id(), auditDetails);
            if ("FAILED".equals(result.status())) {
                break;
            }
        }
        String status = results.stream().anyMatch(result -> "FAILED".equals(result.status())) ? "FAILED" : "SUCCESS";
        auditLogger.record(user, "PLAN_EXECUTION_" + status, plan.id(), null, Map.of("actionCount", results.size()));
        return new ExecutePlanResponse(plan.id(), status, results);
    }

    private PlanResponse toResponse(AgentPlan plan) {
        List<AgentActionResponse> actions = plan.actions().stream()
                .map(action -> new AgentActionResponse(
                        action.id(),
                        action.tool().name(),
                        action.title(),
                        action.description(),
                        action.requiresConfirmation(),
                        action.parameters()))
                .toList();
        return new PlanResponse(
                plan.id(),
                plan.summary(),
                plan.explanation(),
                actions.stream().anyMatch(AgentActionResponse::requiresConfirmation),
                plan.safetyNotes(),
                actions);
    }
}
