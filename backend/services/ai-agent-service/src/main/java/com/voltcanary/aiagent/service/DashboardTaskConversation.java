package com.voltcanary.aiagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.voltcanary.aiagent.config.AiTaskProperties;
import com.voltcanary.aiagent.domain.DashboardTaskState;
import com.voltcanary.aiagent.domain.DashboardTurn;
import com.voltcanary.aiagent.dto.DashboardTaskRecovery;
import com.voltcanary.aiagent.dto.DashboardTaskSummary;
import com.voltcanary.aiagent.dto.DashboardTurnMetadata;
import com.voltcanary.aiagent.dto.PlanRequest;
import com.voltcanary.aiagent.dto.PlanResponse;
import com.voltcanary.aiagent.port.DashboardTasks;
import com.voltcanary.aiagent.security.AuthenticatedUser;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Working-turn boundary. No remote work runs inside a task persistence transaction. */
@Service
public class DashboardTaskConversation {
    private final DashboardTasks tasks;
    private final AiAgentOrchestrator orchestrator;
    private final AuthorizedPlanningContext contexts;
    private final PlanClarifications clarifications;
    private final DashboardPlanningContext dashboards;
    private final PlanningInputGuard guard;
    private final AiTaskProperties properties;
    private final ObjectMapper mapper;

    public DashboardTaskConversation(DashboardTasks tasks, AiAgentOrchestrator orchestrator,
            AuthorizedPlanningContext contexts, PlanClarifications clarifications, DashboardPlanningContext dashboards,
            PlanningInputGuard guard, AiTaskProperties properties, ObjectMapper mapper) {
        this.tasks = tasks;
        this.orchestrator = orchestrator;
        this.contexts = contexts;
        this.clarifications = clarifications;
        this.dashboards = dashboards;
        this.guard = guard;
        this.properties = properties;
        this.mapper = mapper.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }

    public PlanResponse create(AuthenticatedUser user, PlanRequest request, String authorization) {
        guard.validate(request.message(), request.context());
        var metadata = request.metadata();
        validate(metadata);
        if (!dashboards.requested(request.message()) && !(metadata.taskId() != null && metadata.expectedTaskRevision() != null
                && metadata.expectedTaskRevision() > 0)) {
            return orchestrator.createPlan(user, request.message(), request.context(), request.providerId(), request.modelConfigId(), authorization);
        }
        var scope = contexts.scope(user, authorization);
        var input = request.context() == null ? Map.<String, Object>of() : request.context();
        String fingerprint = hash(request.message(), input, null, metadata, request.providerId(), request.modelConfigId());
        if (metadata.requestId() != null) {
            var prior = tasks.request(scope.userId(), scope.accountId(), metadata.requestId());
            if (prior.isPresent()) return replay(user, prior.get(), fingerprint, authorization);
        }
        DashboardTaskState state;
        boolean fresh = metadata.taskId() == null || metadata.expectedTaskRevision() == null || metadata.expectedTaskRevision() == 0;
        if (fresh) {
            if (metadata.baseReviewId() != null) throw conflict("An initial turn cannot select a review");
            if (metadata.branchId() != null && !metadata.branchId().equals(metadata.taskId())) throw conflict("Initial branch must match its task");
            state = initial(scope, metadata.taskId(), request.message(), input, request.providerId(), request.modelConfigId(), null, null);
        } else {
            state = tasks.find(scope.userId(), scope.accountId(), metadata.taskId(),
                    metadata.branchId() == null ? metadata.taskId() : metadata.branchId());
            if (state.lastAcceptedReviewId() != null) throw conflict("Continue through the selected review");
        }
        return perform(user, state, request.message(), input, null, metadata, fresh, fingerprint, authorization);
    }

    public PlanResponse clarify(AuthenticatedUser user, UUID parentId, String message,
            DashboardTurnMetadata metadata, String authorization) {
        validate(metadata);
        guard.validate(message, Map.of());
        if (message.length() > 2000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Clarification exceeds 2000 characters");
        var scope = contexts.scope(user, authorization);
        String fingerprint = hash(message, Map.of(), parentId, metadata, null, null);
        if (metadata.requestId() != null) {
            var prior = tasks.request(scope.userId(), scope.accountId(), metadata.requestId());
            if (prior.isPresent()) return replay(user, prior.get(), fingerprint, authorization);
        }
        var continuation = clarifications.prepare(user, parentId, message, authorization);
        var linked = tasks.review(scope.userId(), scope.accountId(), parentId);
        if (linked.isEmpty() && !dashboards.requested(continuation.message())) {
            return orchestrator.clarify(user, parentId, message, authorization);
        }
        if (metadata.baseReviewId() != null && !metadata.baseReviewId().equals(parentId)) throw conflict("Selected review changed");
        DashboardTaskState state;
        boolean fresh;
        if (linked.isPresent()) {
            var snapshot = linked.get().outcome();
            if (metadata.taskId() != null && !metadata.taskId().equals(snapshot.taskId())
                    || metadata.branchId() != null && !metadata.branchId().equals(snapshot.branchId())) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Dashboard task not found");
            }
            var current = tasks.find(scope.userId(), scope.accountId(), snapshot.taskId(), snapshot.branchId());
            fresh = metadata.requestId() == null || DashboardReviewHistory.reviewed(continuation.parent()) && linked.get().revision() < current.revision()
                    && !parentId.equals(current.lastAcceptedReviewId());
            state = fresh ? branch(snapshot) : current;
        } else {
            if (metadata.expectedTaskRevision() != null && metadata.expectedTaskRevision() != 0
                    || metadata.branchId() != null && !metadata.branchId().equals(metadata.taskId())) {
                throw conflict("Legacy review requires an initial working revision");
            }
            var input = continuation.input();
            String original = input.get("originalDashboardRequest") instanceof String text ? text : continuation.parent().plan().userMessage();
            UUID acceptedReview = continuation.parent().plan().actions().stream().anyMatch(action -> action.tool()
                    == com.voltcanary.aiagent.model.AgentTool.CREATE_DASHBOARD && action.parameters().get("definition") instanceof Map<?, ?>)
                    ? parentId : null;
            state = initial(scope, metadata.taskId(), original, input, continuation.parent().plan().providerId(),
                    continuation.parent().plan().modelConfigId(), acceptedReview, continuation.parent().expiresAt());
            fresh = true;
        }
        return perform(user, state, message, continuation.input(), parentId, metadata, fresh, fingerprint, authorization);
    }

    public PlanResponse getPlan(AuthenticatedUser user, UUID planId, String authorization) {
        var response = orchestrator.getPlan(user, planId, authorization);
        var scope = contexts.scope(user, authorization);
        return tasks.review(scope.userId(), scope.accountId(), planId)
                .filter(turn -> turn.outcome() != null).map(turn -> response.withTask(DashboardTaskSummary.of(turn.outcome())))
                .orElse(response);
    }

    public DashboardTaskRecovery recover(AuthenticatedUser user, UUID taskId, UUID branchId, String authorization) {
        var scope = contexts.scope(user, authorization);
        var state = tasks.find(scope.userId(), scope.accountId(), taskId, branchId == null ? taskId : branchId);
        PlanResponse review = null;
        String selectionStatus = "AVAILABLE";
        try {
            contexts.revalidate(state.selection().deviceIds().stream().map(id -> new AuthorizedPlanningContext.ResourceRef(
                    UUID.randomUUID().toString(), "device", id.toString(), Map.of())).toList(), authorization);
            if (state.lastAcceptedReviewId() != null) review = orchestrator.getPlan(user, state.lastAcceptedReviewId(), authorization);
        } catch (ResponseStatusException unavailable) {
            if (unavailable.getStatusCode().value() != 403 && unavailable.getStatusCode().value() != 404) throw unavailable;
            selectionStatus = "UNAVAILABLE";
        }
        var summary = DashboardTaskSummary.of(state).withSelectionStatus(selectionStatus);
        return new DashboardTaskRecovery(summary, review == null ? null : review.withTask(summary));
    }

    private PlanResponse perform(AuthenticatedUser user, DashboardTaskState state, String message,
            Map<String, Object> input, UUID parent, DashboardTurnMetadata metadata, boolean fresh, String hash, String authorization) {
        guard.validate(message, input);
        UUID requestId = metadata.requestId() == null ? UUID.randomUUID() : metadata.requestId();
        var existing = tasks.request(state.userId(), state.accountId(), requestId);
        if (existing.isPresent()) return replay(user, existing.get(), hash, authorization);
        var turn = new DashboardTurn(UUID.randomUUID(), requestId, state.taskId(), state.branchId(), state.userId(), state.accountId(),
                hash, parent, state.revision() + 1, message, "PENDING", null, null, Instant.now().plusSeconds(900), null);
        DashboardTaskState accepted;
        try {
            accepted = tasks.accept(state, turn, metadata.expectedTaskRevision(), fresh);
        } catch (ResponseStatusException conflict) {
            var duplicate = tasks.request(state.userId(), state.accountId(), requestId);
            if (duplicate.isPresent()) return replay(user, duplicate.get(), hash, authorization);
            throw conflict;
        }
        var working = new AtomicReference<>(accepted);
        try {
            if (parent != null && Boolean.TRUE.equals(input.get("sourceSelectionReply")) && input.get("sourceId") instanceof String selectedSource) {
                UUID sourceDevice = input.get("sourceDeviceId") instanceof String selectedDevice ? UUID.fromString(selectedDevice)
                        : working.get().selection().deviceIds().size() == 1 ? working.get().selection().deviceIds().getFirst() : null;
                var next = working.get().confirmSource(selectedSource, sourceDevice);
                tasks.checkpoint(next);
                working.set(next);
            }
            if (fresh && parent != null && state.name() == null) {
                String legacyName = DashboardTaskContext.confirmedLegacyName(input, state.originalRequest());
                if (legacyName != null) checkpoint(working, legacyName, DashboardTaskState.Provenance.USER_EXPLICIT);
            }
            String acceptedDefault = metadata.acceptedSuggestion() && input.get("priorSuggestedValues") instanceof Map<?, ?> suggestions
                    && suggestions.get("name") instanceof String name && !name.isBlank() && name.length() <= 80 ? name : null;
            if (acceptedDefault != null) checkpoint(working, acceptedDefault, DashboardTaskState.Provenance.USER_ACCEPTED_DEFAULT);
            String explicit = DashboardTaskContext.explicitName(message, parent == null && fresh ? input : Map.of());
            if (explicit != null) checkpoint(working, explicit, explicit.equals(acceptedDefault)
                    ? DashboardTaskState.Provenance.USER_ACCEPTED_DEFAULT : DashboardTaskState.Provenance.USER_EXPLICIT);
            var values = new LinkedHashMap<>(input);
            values.putAll(DashboardTaskContext.input(working.get()));
            values.put("dashboardTurnId", turn.turnId().toString());
            java.util.function.Consumer<String> name = candidate -> {
                if (explicit != null && !explicit.equals(candidate)) return;
                if (working.get().name() != null && !working.get().name().value().equals(candidate)
                        && explicit == null && !metadata.acceptedSuggestion() && !DashboardTaskContext.grounded(candidate, message)) return;
                if (candidate.equals(acceptedDefault)) {
                    checkpoint(working, candidate, DashboardTaskState.Provenance.USER_ACCEPTED_DEFAULT);
                } else if (DashboardTaskContext.grounded(candidate, message)) {
                    checkpoint(working, candidate, DashboardTaskState.Provenance.USER_EXPLICIT);
                }
            };
            String combined = state.originalRequest() + "\n\nУточнение пользователя:\n" + message;
            var result = parent == null ? orchestrator.createPlan(user, fresh ? message : combined, values,
                    state.providerId(), state.modelConfigId(), authorization, name)
                    : orchestrator.clarify(user, parent, message, authorization, values, name);
            String failure = result.safetyNotes().stream().anyMatch(note -> note.contains("Provider request failed"))
                    ? "PROVIDER_FAILURE" : result.summary().equals("Не удалось подготовить изменения") ? "PREPARATION_FAILED" : null;
            if (result.restoredFromReviewId() != null) {
                var snapshot = tasks.review(state.userId(), state.accountId(), result.restoredFromReviewId())
                        .map(DashboardTurn::outcome).orElse(null);
                var parameters = result.actions().getFirst().parameters();
                @SuppressWarnings("unchecked")
                var intent = (Map<String, Object>) parameters.get("prepareIntent");
                @SuppressWarnings("unchecked")
                var definition = (Map<String, Object>) parameters.get("definition");
                if (explicit != null && !explicit.equals(definition.get("name"))) {
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Stored restore conflicts with the explicit dashboard name");
                }
                var selection = snapshot != null ? snapshot.selection() : DashboardTaskContext.selection(intent == null ? Map.of() : intent);
                working.set(working.get().restoreTo(snapshot, String.valueOf(definition.get("name")), selection));
            }
            var outcome = working.get().finish(result.planId(), "READY".equals(result.planningStatus()), failure);
            tasks.finish(outcome, finished(turn, outcome, result.planId(), failure));
            return result.withTask(DashboardTaskSummary.of(outcome));
        } catch (RuntimeException failure) {
            String category = failure instanceof ResponseStatusException response
                    ? "HTTP_" + response.getStatusCode().value() : "PLANNING_FAILED";
            var outcome = working.get().finish(null, false, category);
            tasks.finish(outcome, finished(turn, outcome, null, category));
            throw failure;
        }
    }

    private void checkpoint(AtomicReference<DashboardTaskState> working, String name, DashboardTaskState.Provenance provenance) {
        var next = working.get().confirmName(name, provenance);
        tasks.checkpoint(next);
        working.set(next);
    }

    private PlanResponse replay(AuthenticatedUser user, DashboardTurn turn, String hash, String authorization) {
        if (!turn.payloadHash().equals(hash)) throw conflict("Request identity already has a different payload");
        tasks.find(turn.userId(), turn.accountId(), turn.taskId(), turn.branchId());
        if (turn.reviewId() != null) return orchestrator.getPlan(user, turn.reviewId(), authorization)
                .withTask(DashboardTaskSummary.of(turn.outcome()));
        if ("PENDING".equals(turn.status())) throw conflict("Dashboard turn in progress; recover its task");
        int code = turn.failureCategory() != null && turn.failureCategory().matches("HTTP_[45][0-9]{2}")
                ? Integer.parseInt(turn.failureCategory().substring(5)) : 503;
        throw new ResponseStatusException(HttpStatus.valueOf(code), "Recorded planning failure; send a new request to retry");
    }

    private DashboardTaskState initial(AuthorizedPlanningContext.Scope scope, UUID requestedId, String original,
            Map<String, Object> input, UUID provider, UUID model, UUID review, Instant reviewExpiry) {
        UUID id = requestedId == null ? UUID.randomUUID() : requestedId;
        Instant expires = Instant.now().plus(properties.getExpiry());
        if (reviewExpiry != null && reviewExpiry.isBefore(expires)) expires = reviewExpiry;
        return new DashboardTaskState(1, id, id, scope.userId(), scope.accountId(), 0, original, null,
                DashboardTaskContext.selection(input), provider, model, review, null, "NEW", null,
                expires, List.of());
    }

    private DashboardTaskState branch(DashboardTaskState snapshot) {
        return new DashboardTaskState(1, snapshot.taskId(), UUID.randomUUID(), snapshot.userId(), snapshot.accountId(),
                snapshot.revision(), snapshot.originalRequest(), snapshot.name(), snapshot.selection(), snapshot.providerId(),
                snapshot.modelConfigId(), snapshot.lastAcceptedReviewId(), null, "NEW", null, snapshot.expiresAt(), snapshot.pending());
    }

    private DashboardTurn finished(DashboardTurn turn, DashboardTaskState state, UUID review, String failure) {
        return new DashboardTurn(turn.turnId(), turn.requestId(), turn.taskId(), turn.branchId(), turn.userId(), turn.accountId(),
                turn.payloadHash(), turn.baseReviewId(), turn.revision(), turn.message(), failure == null ? "COMPLETED" : "FAILED",
                failure, review, turn.leaseUntil(), state);
    }

    private String hash(String message, Map<String, Object> input, UUID parent, DashboardTurnMetadata metadata, UUID provider, UUID model) {
        try {
            var payload = new LinkedHashMap<String, Object>();
            payload.put("message", message);
            payload.put("input", input);
            payload.put("parent", parent);
            payload.put("metadata", metadata);
            payload.put("provider", provider);
            payload.put("model", model);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(payload)));
        } catch (Exception failure) { throw new IllegalStateException("Cannot fingerprint dashboard turn", failure); }
    }

    private ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }

    private void validate(DashboardTurnMetadata metadata) {
        if (metadata.expectedTaskRevision() != null && metadata.expectedTaskRevision() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Task revision must be nonnegative");
        }
    }
}
