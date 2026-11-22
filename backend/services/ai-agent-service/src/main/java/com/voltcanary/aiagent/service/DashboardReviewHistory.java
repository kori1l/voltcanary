package com.voltcanary.aiagent.service;

import com.voltcanary.aiagent.model.AgentAction;
import com.voltcanary.aiagent.model.AgentPlan;
import com.voltcanary.aiagent.model.AgentTool;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Owner-scoped immutable review ancestry, independent of model-generated layouts. */
final class DashboardReviewHistory {

    static final String RESTORED_FROM = "_restoredFromReviewId";
    private final PlanStore store;
    private final AuthorizedPlanningContext contexts;

    DashboardReviewHistory(PlanStore store, AuthorizedPlanningContext contexts) {
        this.store = store;
        this.contexts = contexts;
    }

    static boolean reviewed(PlanStore.Stored value) {
        return (
            "READY".equals(value.status()) &&
            value.plan().actions().size() == 1 &&
            value.plan().actions().getFirst().tool() == AgentTool.CREATE_DASHBOARD &&
            value.plan().actions().getFirst().parameters().containsKey("definition")
        );
    }

    private List<PlanStore.Stored> ancestors(PlanStore.Stored current) {
        return ancestors(current, null);
    }

    private static boolean available(PlanStore.Stored value, String parentKey) {
        return (
            reviewed(value) ||
            (parentKey != null &&
                "SUPERSEDED".equals(value.status()) &&
                value.plan().actions().size() == 1 &&
                value.plan().actions().getFirst().tool() == AgentTool.CREATE_DASHBOARD &&
                value.plan().actions().getFirst().parameters().containsKey("definition"))
        );
    }

    private List<PlanStore.Stored> ancestors(PlanStore.Stored current, String parentKey) {
        return ancestors(current, parentKey, value -> available(value, parentKey));
    }

    private List<PlanStore.Stored> ancestors(
        PlanStore.Stored current,
        String parentKey,
        java.util.function.Predicate<PlanStore.Stored> eligible
    ) {
        var result = new ArrayList<PlanStore.Stored>();
        var scope = new AuthorizedPlanningContext.Scope(
            current.plan().userId(),
            current.accountId()
        );
        var seen = new java.util.HashSet<UUID>();
        seen.add(current.plan().id());
        for (int count = 0; count < 30; count++) {
            var context = store.context(current.plan().id(), scope);
            Object linked = parentKey == null ? null : context.get(parentKey);
            if (linked == null && context.get("_clarification") instanceof Map<?, ?> state) linked =
                state.get("parentPlanId");
            if (!(linked instanceof String parent)) break;
            UUID id;
            try {
                id = UUID.fromString(parent);
            } catch (IllegalArgumentException invalid) {
                break;
            }
            if (!seen.add(id)) break;
            try {
                current = store.find(id, scope);
            } catch (ResponseStatusException absent) {
                if (absent.getStatusCode().value() == 404) break;
                throw absent;
            }
            if (eligible.test(current)) result.add(current);
        }
        return result;
    }

    List<UUID> previousIds(PlanStore.Stored current) {
        var values = new ArrayList<>(
            ancestors(current)
                .stream()
                .map(item -> item.plan().id())
                .toList()
        );
        java.util.Collections.reverse(values);
        return List.copyOf(values);
    }

    CreationTaskPlanning.PreparedTask restore(
        AgentPlan requested,
        PlanStore.Stored parent,
        String authorization
    ) {
        return restore(requested, parent, authorization, null);
    }

    CreationTaskPlanning.PreparedTask restore(
        AgentPlan requested,
        PlanStore.Stored parent,
        String authorization,
        String parentKey
    ) {
        return restore(requested, parent, authorization, parentKey, value ->
            available(value, parentKey)
        );
    }

    CreationTaskPlanning.PreparedTask restoreDraft(
        AgentPlan requested,
        PlanStore.Stored parent,
        String authorization,
        DashboardDraftTarget drafts,
        String dashboardId
    ) {
        return restore(
            requested,
            parent,
            authorization,
            "v2ParentReviewId",
            value ->
                value.plan().actions().size() == 1 &&
                value.plan().actions().getFirst().parameters().containsKey("definition") &&
                dashboardId.equals(drafts.savedId(value))
        );
    }

    private CreationTaskPlanning.PreparedTask restore(
        AgentPlan requested,
        PlanStore.Stored parent,
        String authorization,
        String parentKey,
        java.util.function.Predicate<PlanStore.Stored> eligible
    ) {
        var parameters = requested.actions().getFirst().parameters();
        boolean keep = parameters.equals(Map.of("keepReview", true));
        Object raw = parameters.get("restoreSteps");
        PlanStore.Stored target;
        if (keep) {
            if (!eligible.test(parent)) throw invalid();
            target = parent;
        } else {
            if (
                parameters.size() != 1 ||
                !(raw instanceof Number steps) ||
                steps.doubleValue() != steps.intValue() ||
                steps.intValue() < 1 ||
                steps.intValue() > 30
            ) throw invalid();
            var older = ancestors(parent, parentKey, eligible);
            if (steps.intValue() > older.size()) throw invalid();
            target = older.get(steps.intValue() - 1);
        }
        contexts.revalidate(target.refs(), authorization);
        var original = target.plan().actions().getFirst();
        Map<String, Object> reviewed = original.parameters();
        if (keep && DashboardOperationProtocol.VERSION.equals(reviewed.get("planningProtocol"))) {
            var metadata = new java.util.LinkedHashMap<>(reviewed);
            metadata.put("operationChanges", Map.of());
            metadata.put("expectedOutcomes", List.of());
            metadata.put("responsiveReflow", false);
            reviewed = Map.copyOf(metadata);
        }
        var action = new AgentAction(
            UUID.randomUUID(),
            original.tool(),
            original.title(),
            original.description(),
            true,
            reviewed
        );
        var plan = new AgentPlan(
            requested.id(),
            requested.userId(),
            requested.userMessage(),
            keep ? "Вариант сохранён без изменений" : "Восстановлен предыдущий вариант",
            keep
                ? "Сохранён точный текущий вариант дашборда без изменений."
                : "Восстановлен точный сохранённый вариант дашборда. Проверьте предпросмотр.",
            requested.safetyNotes(),
            List.of(action),
            requested.createdAt(),
            requested.providerType(),
            requested.providerName(),
            requested.modelId(),
            requested.providerId(),
            requested.modelConfigId(),
            requested.fallbackUsed()
        );
        var scope = new AuthorizedPlanningContext.Scope(parent.plan().userId(), parent.accountId());
        var values = new java.util.LinkedHashMap<>(store.context(target.plan().id(), scope));
        if (keep) values.remove(RESTORED_FROM);
        else values.put(RESTORED_FROM, target.plan().id().toString());
        return new CreationTaskPlanning.PreparedTask(
            plan,
            new AuthorizedPlanningContext.Prepared(scope, Map.copyOf(values), target.refs())
        );
    }

    private ResponseStatusException invalid() {
        return new ResponseStatusException(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "Выберите доступный предыдущий вариант дашборда в истории."
        );
    }
}
