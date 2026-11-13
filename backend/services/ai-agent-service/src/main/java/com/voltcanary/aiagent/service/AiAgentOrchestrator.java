package com.voltcanary.aiagent.service;

import com.voltcanary.aiagent.audit.AuditLogger;
import com.voltcanary.aiagent.exception.ModelPlanningFailedException;
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

    private final CreationTaskPlanning planning;
    private final PlanStore planStore;
    private final ToolExecutor toolExecutor;
    private final AuditLogger auditLogger;
    private final AuthorizedPlanningContext contexts;
    private final CreationReconciliation reconciliation;
    private final PlanClarifications clarifications;
    private final DashboardReviewHistory reviews;

    public AiAgentOrchestrator(CreationTaskPlanning planning, PlanStore planStore, ToolExecutor toolExecutor, AuditLogger auditLogger,
            AuthorizedPlanningContext contexts, CreationReconciliation reconciliation, PlanClarifications clarifications) {
        this.planning = planning;
        this.planStore = planStore;
        this.toolExecutor = toolExecutor;
        this.auditLogger = auditLogger;
        this.contexts = contexts;
        this.reconciliation = reconciliation;
        this.clarifications = clarifications;
        this.reviews = new DashboardReviewHistory(planStore, contexts);
    }

    public PlanResponse createPlan(AuthenticatedUser user, String message, Map<String, Object> context, UUID providerId,
            UUID modelConfigId, String authorization) {
        return createPlan(user, message, context, providerId, modelConfigId, authorization, null);
    }

    public PlanResponse createPlan(AuthenticatedUser user, String message, Map<String, Object> context, UUID providerId,
            UUID modelConfigId, String authorization, java.util.function.Consumer<String> confirmedName) {
        var prepared = confirmedName == null ? planning.prepare(user, message, context, providerId, modelConfigId, authorization)
                : planning.prepare(user, message, context, providerId, modelConfigId, authorization, null, confirmedName);
        AgentPlan plan = prepared.plan();
        var saved = planStore.save(plan, clarifications.remember(prepared.context(), plan, context, 0, null, List.of()));
        recordReview(saved);
        auditLogger.record(user, "PLAN_CREATED", plan.id(), null, Map.of("actionCount", plan.actions().size()));
        return toResponse(saved);
    }

    public PlanResponse clarify(AuthenticatedUser user, UUID parentId, String answer, String authorization) {
        return clarify(user, parentId, answer, authorization, Map.of(), null);
    }

    public PlanResponse clarify(AuthenticatedUser user, UUID parentId, String answer, String authorization,
            Map<String, Object> working, java.util.function.Consumer<String> confirmedName) {
        var continuation = clarifications.prepare(user, parentId, answer, authorization);
        var parent = continuation.parent().plan();
        var input = new LinkedHashMap<>(continuation.input());
        input.putAll(working);
        java.util.function.Function<AgentPlan, CreationTaskPlanning.PreparedTask> restore =
                draft -> reviews.restore(draft, continuation.parent(), authorization);
        var prepared = confirmedName == null ? planning.prepare(user, continuation.message(), input, parent.providerId(),
                parent.modelConfigId(), authorization, restore) : planning.prepare(user, continuation.message(), input,
                parent.providerId(), parent.modelConfigId(), authorization, restore, confirmedName);
        if (prepared.plan().safetyNotes().stream().anyMatch(note -> note.contains("Provider request failed"))) {
            throw new ModelPlanningFailedException(prepared.plan().safetyNotes().contains("MODEL_OUTPUT_LIMIT"));
        }
        var current = planStore.find(parentId, prepared.context().scope());
        if (!List.of("READY", "NEEDS_INPUT").contains(current.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Parent task changed; check its current status");
        }
        var saved = planStore.save(prepared.plan(), clarifications.remember(prepared.context(), prepared.plan(), input,
                continuation.round(), parentId, continuation.answers()));
        recordReview(saved);
        auditLogger.record(user, "PLAN_CLARIFIED", saved.plan().id(), null,
                Map.of("parentPlanId", parentId.toString(), "round", continuation.round()));
        return toResponse(saved);
    }

    public PlanResponse getPlan(AuthenticatedUser user, UUID planId, String authorization) {
        var scope = contexts.scope(user, authorization);
        var saved = planStore.find(planId, scope);
        contexts.revalidate(saved.refs(), authorization);
        contexts.revalidateCreatedDevices(saved.execution(), authorization);
        return toResponse(saved);
    }

    public ExecutePlanResponse execute(AuthenticatedUser user, UUID planId, boolean confirmed, String authorization,
            Integer expectedVersion, String digest) {
        var scope = contexts.scope(user, authorization);
        var saved = planStore.find(planId, scope);
        AgentPlan plan = saved.plan();
        contexts.revalidate(saved.refs(), authorization);
        contexts.revalidateCreatedDevices(saved.execution(), authorization);
        boolean needsConfirmation = plan.actions().stream().anyMatch(AgentAction::requiresConfirmation);
        if (needsConfirmation && !confirmed) {
            auditLogger.record(user, "EXECUTION_REQUIRES_CONFIRMATION", plan.id(), null, Map.of("confirmed", false));
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Confirmation is required for mutating actions");
        }
        boolean claimed = planStore.claim(saved, scope, expectedVersion, digest);
        boolean recovering = !claimed && "UNKNOWN".equals(saved.status())
                && plan.actions().size() == 1 && reconciliation.supports(plan.actions().getFirst());
        boolean nativeInspectionOnly = recovering && plan.actions().getFirst().tool() == com.voltcanary.aiagent.model.AgentTool.CREATE_NATIVE_TABLE
                && !saved.expiresAt().isAfter(java.time.Instant.now());
        if (recovering) claimed = nativeInspectionOnly ? planStore.claimNativeReceiptInspection(saved, scope) : planStore.claimReconciliation(saved, scope);
        if (!claimed) {
            var current = planStore.find(planId, scope);
            return current.execution() != null ? current.execution()
                    : new ExecutePlanResponse(planId, current.status(), planStore.actionResults(current.plan()));
        }
        if (confirmed && !nativeInspectionOnly) planStore.recordConfirmation(saved, scope);

        Map<String, Object> outputs = new LinkedHashMap<>();
        List<ActionExecutionResponse> results = new ArrayList<>();
        auditLogger.record(user, "PLAN_EXECUTION_STARTED", plan.id(), null, Map.of("confirmed", confirmed));
        for (AgentAction action : plan.actions()) {
            try (var trace = com.voltcanary.aiagent.trace.AssistantTrace.start(recovering ? "RECONCILIATION" : "TOOL_EXECUTION",
                    Map.of("reviewId", plan.id(), "actionId", action.id(), "tool", action.tool().name()))) {
            planStore.actionStarted(saved, scope, action);
            auditLogger.record(user, "ACTION_EXECUTION_STARTED", plan.id(), action.id(), Map.of("tool", action.tool().name()));
            String key = PlanStore.actionKey(scope, planId, saved.version(), action.id());
            ActionExecutionResponse result = recovering
                    ? reconciliation.reconcile(action, authorization, user.userId(), outputs, key, nativeInspectionOnly)
                    : toolExecutor.execute(action, authorization, user.userId(), outputs, key);
            planStore.actionFinished(saved, scope, result);
            results.add(result);
            Map<String, Object> auditDetails = new LinkedHashMap<>();
            auditDetails.put("tool", action.tool().name());
            auditDetails.put("error", result.error());
            auditLogger.record(user, "ACTION_EXECUTION_" + result.status(), plan.id(), action.id(), auditDetails);
            if (!"SUCCESS".equals(result.status())) {
                trace.failure("ACTION_" + result.status(), Map.of("result", result.result() == null ? Map.of() : result.result()));
                break;
            }
            trace.success(Map.of("result", result.result() == null ? Map.of() : result.result()));
            }
        }
        boolean unknown = results.stream().anyMatch(result -> "UNKNOWN".equals(result.status()));
        boolean failed = results.stream().anyMatch(result -> "FAILED".equals(result.status()));
        boolean succeeded = results.stream().anyMatch(result -> "SUCCESS".equals(result.status()));
        String status = unknown ? "UNKNOWN" : failed ? succeeded ? "PARTIAL_SUCCESS" : "FAILED" : "SUCCESS";
        auditLogger.record(user, "PLAN_EXECUTION_" + status, plan.id(), null, Map.of("actionCount", results.size()));
        var response = new ExecutePlanResponse(plan.id(), status, results);
        planStore.finish(saved, scope, response);
        return response;
    }

    private void recordReview(PlanStore.Stored saved) {
        com.voltcanary.aiagent.trace.AssistantTrace.event("REVIEW", "SUCCEEDED", null,
                Map.of("reviewId", saved.plan().id(), "digest", saved.digest(), "version", saved.version(), "status", saved.status()));
    }

    private PlanResponse toResponse(PlanStore.Stored saved) {
        AgentPlan plan = saved.plan();
        List<AgentActionResponse> actions = plan.actions().stream()
                .map(action -> new AgentActionResponse(
                        action.id(),
                        action.tool().name(),
                        action.title(),
                        action.description(),
                        action.requiresConfirmation(),
                        action.parameters()))
                .toList();
        boolean failed = "Не удалось подготовить изменения".equals(plan.summary());
        return new PlanResponse(
                plan.id(),
                plan.summary(),
                plan.explanation(),
                actions.stream().anyMatch(AgentActionResponse::requiresConfirmation),
                plan.safetyNotes(),
                actions,
                plan.providerType(),
                plan.providerName(),
                plan.modelId(),
                plan.providerId(),
                plan.modelConfigId(),
                plan.fallbackUsed(), saved.version(), saved.digest(), saved.expiresAt(),
                failed ? "FAILED" : plan.actions().isEmpty() ? "NEEDS_INPUT" : "READY",
                !failed && plan.actions().isEmpty() ? List.of(plan.explanation()) : List.of(),
                saved.status(), saved.execution() != null ? saved.execution()
                        : List.of("RUNNING", "UNKNOWN").contains(saved.status())
                        ? new ExecutePlanResponse(plan.id(), saved.status(), planStore.actionResults(plan)) : null,
                !failed && plan.actions().isEmpty()?plan.clarification():null, reviews.previousIds(saved), null, restoredFrom(saved));
    }

    private UUID restoredFrom(PlanStore.Stored saved) {
        Object id = planStore.context(saved.plan().id(), new AuthorizedPlanningContext.Scope(saved.plan().userId(), saved.accountId()))
                .get(DashboardReviewHistory.RESTORED_FROM);
        try { return id instanceof String value ? UUID.fromString(value) : null; }
        catch (IllegalArgumentException invalid) { return null; }
    }
}
