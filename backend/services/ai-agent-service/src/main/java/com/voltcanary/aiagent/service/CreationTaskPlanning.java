package com.voltcanary.aiagent.service;

import com.voltcanary.aiagent.model.AgentPlan;
import com.voltcanary.aiagent.model.AgentTool;
import com.voltcanary.aiagent.security.AuthenticatedUser;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class CreationTaskPlanning {
    public record PreparedTask(AgentPlan plan, AuthorizedPlanningContext.Prepared context) { }
    private final ActionPlanner planner;
    private final AuthorizedPlanningContext contexts;
    private final DashboardPlanningContext dashboardContext;
    private final DeviceCreationCompiler devices;
    private final DashboardCreationCompiler dashboards;
    private final PlanningInputGuard guard;

    public CreationTaskPlanning(ActionPlanner planner, AuthorizedPlanningContext contexts, DashboardPlanningContext dashboardContext,
            DeviceCreationCompiler devices, DashboardCreationCompiler dashboards, PlanningInputGuard guard) {
        this.planner = planner;
        this.contexts = contexts;
        this.dashboardContext = dashboardContext;
        this.devices = devices;
        this.dashboards = dashboards;
        this.guard = guard;
    }

    public PreparedTask prepare(AuthenticatedUser user, String message, Map<String, Object> input,
            UUID providerId, UUID modelId, String authorization) {
        return prepare(user, message, input, providerId, modelId, authorization, null);
    }

    public PreparedTask prepare(AuthenticatedUser user, String message, Map<String, Object> input,
            UUID providerId, UUID modelId, String authorization,
            java.util.function.Function<AgentPlan, PreparedTask> restore) {
        return prepare(user, message, input, providerId, modelId, authorization, restore, name -> { });
    }

    public PreparedTask prepare(AuthenticatedUser user, String message, Map<String, Object> input,
            UUID providerId, UUID modelId, String authorization,
            java.util.function.Function<AgentPlan, PreparedTask> restore, java.util.function.Consumer<String> confirmedName) {
        boolean explicitDashboard = input != null && "DASHBOARD".equals(input.get("taskKind"));
        boolean dashboard = explicitDashboard || !(input != null && input.containsKey("originalDeviceRequest")
                || DeviceTaskContext.requested(message)) && dashboardContext.requested(message);
        var values = new java.util.LinkedHashMap<String, Object>(input == null ? Map.of() : input);
        if (dashboard) {
            values.putIfAbsent("originalDashboardRequest", message);
            String explicitName = DashboardTaskName.explicit(message, values);
            if (explicitName != null) values.put("name", explicitName);
        } else {
            values = new java.util.LinkedHashMap<>(DeviceTaskContext.prepare(message, values));
        }
        com.voltcanary.aiagent.trace.AssistantTrace.event("CONTEXT", "STARTED", null,
                Map.of("inputFields", values.keySet(), "selectedDeviceId", String.valueOf(values.get("deviceId")), "dashboard", dashboard));
        var context = contexts.prepare(user, authorization, message, values);
        if (dashboard) {
            try (var capabilities = com.voltcanary.aiagent.trace.AssistantTrace.start("CAPABILITIES", Map.of())) {
                context = dashboardContext.enrich(context, values, authorization);
                capabilities.success(Map.of());
            }
        }
        AgentPlan plan;
        for (int attempt = 0; ; attempt++) {
            plan = planner.plan(user, message, context.context(), providerId, modelId);
            if (!dashboard && context.context().containsKey("originalDeviceRequest") && context.context().containsKey("name")
                    && plan.actions().isEmpty() && plan.explanation() != null
                    && plan.explanation().matches("(?isu).*(как назвать|укажите имя|device name).*")) {
                context = withContext(context, Map.of("deviceIntentRepair", "The device name is already supplied in context.name. Preserve it and prepare the device; ask only about genuinely unknown fields."));
                plan = planner.plan(user, message, context.context(), providerId, modelId);
            }
            if (dashboard && input != null && "DASHBOARD".equals(input.get("taskKind"))
                    && TaskConversationMemory.asksKnown(plan.clarification(), context.context())) {
                plan = new AgentPlan(plan.id(), plan.userId(), plan.userMessage(), "Не удалось подготовить изменения",
                        "Не удалось завершить подготовку. Уже указанные значения сохранены; повторно вводить их не нужно.",
                        plan.safetyNotes(), java.util.List.of(), plan.createdAt(), plan.providerType(), plan.providerName(),
                        plan.modelId(), plan.providerId(), plan.modelConfigId(), plan.fallbackUsed());
                break;
            }
            if (restore != null && plan.actions().size() == 1 && plan.actions().getFirst().tool() == AgentTool.CREATE_DASHBOARD
                    && plan.actions().getFirst().parameters().containsKey("restoreSteps")) return restore.apply(plan);
            if (input != null && "DASHBOARD".equals(input.get("taskKind")) && input.get("name") instanceof String knownName
                    && plan.actions().stream().noneMatch(action -> action.parameters().containsKey("protocol"))) {
                plan = withConfirmedName(plan, knownName);
            }
            // A valid name is task state even when the remaining generated composition is rejected.
            for (var action : plan.actions()) if (action.tool() == AgentTool.CREATE_DASHBOARD) {
                String name = DashboardOperationNames.proposed(action.parameters(),
                        DashboardOperationProtocol.VERSION.equals(context.context().get("dashboardPlanningProtocol")));
                if (name != null && !name.isBlank() && name.length() <= 80 && dashboards.explicitName(message, context, name)) {
                    context = withContext(context, Map.of("name", name));
                    confirmedName.accept(name);
                }
            }
            try {
                if (restore != null && plan.actions().size() == 1 && plan.actions().getFirst().tool() == AgentTool.CREATE_DASHBOARD
                        && plan.actions().getFirst().parameters().containsKey("protocol")) {
                    if (!DashboardOperationProtocol.VERSION.equals(context.context().get("dashboardPlanningProtocol"))) {
                        throw new DashboardPreparationRejected("Semantic operations are disabled for new planning");
                    }
                    var envelope = DashboardOperationProtocol.parse(plan.actions().getFirst().parameters(), new com.fasterxml.jackson.databind.ObjectMapper());
                    if (envelope.operations().getFirst() instanceof com.voltcanary.aiagent.domain.DashboardOperation.Restore
                            || envelope.operations().getFirst() instanceof com.voltcanary.aiagent.domain.DashboardOperation.Keep) {
                        var result = DashboardOperationCompiler.compile(envelope, context, String.valueOf(context.context().get("dashboardTurnId")));
                        var action = plan.actions().getFirst();
                        return restore.apply(new AgentPlan(plan.id(), plan.userId(), plan.userMessage(), plan.summary(), plan.explanation(),
                                plan.safetyNotes(), java.util.List.of(new com.voltcanary.aiagent.model.AgentAction(action.id(), action.tool(), action.title(),
                                action.description(), action.requiresConfirmation(), result.restoreSteps() == 0
                                        ? Map.of("keepReview", true) : Map.of("restoreSteps", result.restoreSteps()))), plan.createdAt(),
                                plan.providerType(), plan.providerName(), plan.modelId(), plan.providerId(), plan.modelConfigId(), plan.fallbackUsed()));
                    }
                }
                com.voltcanary.aiagent.trace.AssistantTrace.event("COMPILATION", "STARTED", null, Map.of("candidateActions", plan.actions()));
                plan = dashboards.compile(plan, context, dashboard, authorization);
                com.voltcanary.aiagent.trace.AssistantTrace.event("COMPILATION", "SUCCEEDED", null, Map.of("reviewId", plan.id(), "actions", plan.actions()));
                break;
            } catch (DashboardSourceChoiceRequired selection) {
                com.voltcanary.aiagent.trace.AssistantTrace.event("COMPILATION", "FAILED", "SOURCE_SELECTION_REQUIRED", Map.of());
                plan = selection.question(plan);
                break;
            } catch (DashboardPreparationRejected rejected) {
                com.voltcanary.aiagent.trace.AssistantTrace.event("COMPILATION", "FAILED", "PREPARATION_REJECTED",
                        Map.of("check", rejected.getMessage(), "candidateActions", plan.actions(), "repairIndex", attempt));
                var repair = new java.util.LinkedHashMap<String, Object>();
                repair.put("compositionRepair", Map.of("diagnostics", rejected.getMessage(),
                        "rejectedParameters", plan.actions().isEmpty() ? Map.of() : plan.actions().getFirst().parameters(),
                        "instruction", "Repair this actual preparation failure using compatible catalogue widgets and fields. Preserve the known name and original requested content. Never ask the user to fix schemas or accept empty changes."));
                if (context.context().containsKey("previousDashboardIntent")) repair.put("pendingDashboardChange",
                        context.context().getOrDefault("pendingDashboardChange", DashboardTaskName.latest(message, context.context())));
                context = withContext(context, repair);
                if (attempt >= 2) {
                    plan = new AgentPlan(plan.id(), plan.userId(), plan.userMessage(), "Не удалось подготовить изменения",
                            "Не удалось подобрать совместимые настройки после повторных попыток. Название и запрос сохранены."
                                    + (context.context().containsKey("previousDashboardIntent") ? " Предыдущий вариант доступен в истории." : " Можно повторить запрос."),
                            plan.safetyNotes(), java.util.List.of(), plan.createdAt(), plan.providerType(), plan.providerName(), plan.modelId(),
                            plan.providerId(), plan.modelConfigId(), plan.fallbackUsed());
                    break;
                }
            }
        }
        if (dashboard) plan = DashboardSourceChoiceRequired.availableOptions(plan, context);
        if (!dashboard) {
            try (var compilation = com.voltcanary.aiagent.trace.AssistantTrace.start("DEVICE_COMPILATION", Map.of("candidateActions", plan.actions()))) {
                plan = devices.compile(plan, context);
                compilation.success(Map.of("reviewId", plan.id(), "actions", plan.actions()));
            }
        }
        if (plan.actions().size() == 1 && plan.actions().getFirst().tool() == AgentTool.CREATE_DEVICE) {
            plan = devices.duplicateReview(plan, contexts.hasDeviceName(plan.actions().getFirst().parameters().get("name").toString(), authorization));
        }
        if (plan.actions().size() > 12) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "At most 12 actions per plan");
        for (var action : plan.actions()) guard.inspect(action.parameters());
        com.voltcanary.aiagent.trace.AssistantTrace.event("VALIDATION", "SUCCEEDED", null, Map.of("reviewId", plan.id(), "actionCount", plan.actions().size()));
        if (!plan.actions().isEmpty()) {
            var refs = new java.util.ArrayList<>(context.refs());
            for (var action : plan.actions()) if (action.tool() == AgentTool.CREATE_DASHBOARD
                    && action.parameters().get("sourceBindings") instanceof java.util.Collection<?> bindings) {
                for (var item : bindings) if (item instanceof Map<?, ?> binding) {
                    String source = String.valueOf(binding.get("sourceId"));
                    if (refs.stream().noneMatch(ref -> ref.kind().equals("source") && ref.resourceId().equals(source))) {
                        refs.add(new AuthorizedPlanningContext.ResourceRef(UUID.randomUUID().toString(), "source", source,
                                Map.of("deviceId", String.valueOf(binding.get("deviceId")))));
                    }
                }
            }
            var mentioned = new java.util.HashSet<String>();
            for (var action : plan.actions()) collect(action.parameters(), mentioned);
            context = new AuthorizedPlanningContext.Prepared(context.scope(), context.context(), refs.stream()
                    .filter(ref -> !ref.kind().equals("widget-descriptor"))
                    .filter(ref -> ref.kind().equals("dashboard-data") || mentioned.contains(ref.ref()) || mentioned.contains(ref.resourceId())).toList());
        }
        context = new AuthorizedPlanningContext.Prepared(context.scope(), context.context(), context.refs().stream()
                .filter(ref -> !ref.kind().equals("widget-descriptor")).toList());
        return new PreparedTask(plan, context);
    }

    private AuthorizedPlanningContext.Prepared withContext(AuthorizedPlanningContext.Prepared context, Map<String, Object> updates) {
        var values = new java.util.LinkedHashMap<>(context.context());
        values.putAll(updates);
        return new AuthorizedPlanningContext.Prepared(context.scope(), Map.copyOf(values), context.refs());
    }

    private AgentPlan withConfirmedName(AgentPlan plan, String name) {
        var actions = plan.actions().stream().map(action -> {
            if (action.tool() != AgentTool.CREATE_DASHBOARD) return action;
            var parameters = new java.util.LinkedHashMap<>(action.parameters());
            parameters.put("name", name);
            return new com.voltcanary.aiagent.model.AgentAction(action.id(), action.tool(), action.title(),
                    action.description(), action.requiresConfirmation(), Map.copyOf(parameters));
        }).toList();
        return new AgentPlan(plan.id(), plan.userId(), plan.userMessage(), plan.summary(), plan.explanation(), plan.safetyNotes(),
                actions, plan.createdAt(), plan.providerType(), plan.providerName(), plan.modelId(), plan.providerId(),
                plan.modelConfigId(), plan.fallbackUsed(), plan.clarification());
    }

    private void collect(Object value, java.util.Set<String> strings) {
        if (value instanceof String text) strings.add(text);
        else if (value instanceof Map<?, ?> map) map.values().forEach(item -> collect(item, strings));
        else if (value instanceof java.util.Collection<?> values) values.forEach(item -> collect(item, strings));
    }
}
