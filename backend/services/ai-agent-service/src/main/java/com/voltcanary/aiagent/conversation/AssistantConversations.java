package com.voltcanary.aiagent.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.voltcanary.aiagent.dto.DashboardTurnMetadata;
import com.voltcanary.aiagent.dto.PlanRequest;
import com.voltcanary.aiagent.dto.PlanResponse;
import com.voltcanary.aiagent.security.AuthenticatedUser;
import com.voltcanary.aiagent.service.DashboardTaskConversation;
import com.voltcanary.aiagent.service.AuthorizedPlanningContext;
import com.voltcanary.aiagent.port.AssistantTraces;
import com.voltcanary.aiagent.trace.AssistantTrace;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AssistantConversations {
    private final ConversationStore store;
    private final DashboardTaskConversation planning;
    private final ObjectMapper mapper;
    private final AuthorizedPlanningContext contexts;
    private final AssistantTraces traces;

    public AssistantConversations(ConversationStore store, DashboardTaskConversation planning, ObjectMapper mapper, AuthorizedPlanningContext contexts, AssistantTraces traces) {
        this.store = store;
        this.planning = planning;
        this.mapper = mapper.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        this.contexts = contexts;
        this.traces = traces;
    }

    public ConversationContracts.Detail detail(AuthenticatedUser user, UUID id, String authorization) {
        var scope = contexts.scope(user, authorization);
        store.reconcile(scope, id);
        var conversation = store.get(scope, id);
        PlanResponse plan = null;
        String resource = "NONE";
        if (conversation.lastPlanId() != null) {
            try {
                plan = ConversationRedaction.value(planning.getPlan(user, conversation.lastPlanId(), authorization), PlanResponse.class, mapper);
                resource = "EXPIRED".equals(plan.executionStatus()) ? "EXPIRED" : "AVAILABLE";
            } catch (ResponseStatusException unavailable) {
                resource = "UNAVAILABLE";
            }
        }
        return new ConversationContracts.Detail(conversation, plan, resource, store.pending(scope, id));
    }

    public ConversationContracts.Detail submit(AuthenticatedUser user, UUID id, ConversationContracts.Submit request, String authorization) {
        // Redact before passing to existing task/plan persistence as well as the transcript.
        var safe = ConversationRedaction.value(request, ConversationContracts.Submit.class, mapper);
        var scope = contexts.scope(user, authorization);
        var attempt = store.begin(scope, id, request, hash(request));
        if (!attempt.claimed()) return detail(user, id, authorization);
        try (var trace = AssistantTrace.open(traces, traces.input(scope, id, safe.requestId()));
                var turn = AssistantTrace.start("TURN", java.util.Map.of("requestId", safe.requestId()))) {
            DashboardTurnMetadata input = safe.metadata();
            UUID parentId = safe.parentPlanId();
            // A dashboard after device setup is a new task even when an older client sends the device review as parent.
            boolean newDashboard = parentId != null && com.voltcanary.aiagent.service.DashboardPlanningContext.recognizesDashboard(safe.text())
                    && planning.getPlan(user, parentId, authorization).actions().stream().anyMatch(action -> "CREATE_DEVICE".equals(action.tool()));
            if (newDashboard) parentId = null;
            var metadata = new DashboardTurnMetadata(safe.requestId(), input == null ? null : input.taskId(),
                    input == null ? null : input.branchId(), input == null ? null : input.expectedTaskRevision(),
                    safe.parentPlanId(), safe.automatic());
            if (newDashboard) metadata = new DashboardTurnMetadata(safe.requestId(), safe.requestId(), safe.requestId(), 0L, null, safe.automatic());
            var resolved = parentId == null
                    ? new ConversationContinuation(store, contexts).resolve(scope, id, safe.text(), safe.context(), authorization)
                    : new ConversationContinuation.Resolution(safe.context(), null);
            if (resolved.question() != null) {
                store.finish(scope, id, safe.requestId(), null, null, resolved.question(), "COMPLETED");
                turn.success(java.util.Map.of("planningStatus", "NEEDS_INPUT"));
                return detail(user, id, authorization);
            }
            PlanResponse response = parentId != null
                    ? planning.clarify(user, parentId, safe.text(), metadata, authorization)
                    : planning.create(user, new PlanRequest(safe.text(), resolved.context(), safe.providerId(), safe.modelConfigId(),
                            safe.requestId(), metadata.taskId(), metadata.branchId(), metadata.expectedTaskRevision(), null), authorization);
            boolean failed = response.task() != null && "FAILED".equals(response.task().status())
                    || response.safetyNotes().stream().anyMatch(note -> note.contains("Provider request failed"))
                    || "Не удалось подготовить изменения".equals(response.summary());
            store.finish(scope, id, safe.requestId(), response.planId(), response.task() == null ? null : response.task().taskId(),
                    response.explanation(), failed ? "FAILED" : "COMPLETED");
            if (failed) turn.failure("PREPARATION_FAILED", java.util.Map.of("reviewId", response.planId()));
            else turn.success(java.util.Map.of("reviewId", response.planId(), "planningStatus", response.planningStatus()));
        } catch (RuntimeException failure) {
            // No raw exception/provider payload is stored or exposed as a transcript message.
            String category = failure instanceof ResponseStatusException response ? "HTTP_" + response.getStatusCode().value() : "PLANNING_FAILED";
            String explanation = "HTTP_404".equals(category) || "HTTP_403".equals(category)
                    ? "Выбранное устройство или другой ресурс недоступен. Выберите доступное устройство и повторите запрос."
                    : "HTTP_503".equals(category)
                    ? "Сервис данных временно недоступен. Запрос сохранён; можно повторить его позже."
                    : "Не удалось подготовить ответ (" + category + "). Можно отправить новое сообщение.";
            store.finish(scope, id, safe.requestId(), null, null, explanation, "FAILED");
        }
        return detail(user, id, authorization);
    }

    private String hash(ConversationContracts.Submit request) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(request)));
        } catch (Exception failure) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid message payload");
        }
    }
}
