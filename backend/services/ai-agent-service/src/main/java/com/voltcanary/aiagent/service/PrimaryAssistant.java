package com.voltcanary.aiagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.aiagent.adapter.PrimaryOrchestrationJournal;
import com.voltcanary.aiagent.config.PrimaryAssistantProperties;
import com.voltcanary.aiagent.conversation.ConversationContracts;
import com.voltcanary.aiagent.conversation.ConversationRedaction;
import com.voltcanary.aiagent.conversation.ConversationStore;
import com.voltcanary.aiagent.dto.ExecutePlanResponse;
import com.voltcanary.aiagent.dto.PlanResponse;
import com.voltcanary.aiagent.llm.adapter.AiModelResolver;
import com.voltcanary.aiagent.llm.adapter.AiResolvedModel;
import com.voltcanary.aiagent.model.PlanningQuestion;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol;
import com.voltcanary.aiagent.orchestration.plan.IntentPlanStore;
import com.voltcanary.aiagent.orchestration.plan.IntentPlans;
import com.voltcanary.aiagent.orchestration.plan.OrchestrationPorts;
import com.voltcanary.aiagent.repository.AiModelConfigRepository;
import com.voltcanary.aiagent.security.AuthenticatedUser;
import com.voltcanary.aiagent.trace.AssistantBoundary;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;

/** Primary HTTP/application bridge. Legacy generation is never a fallback for a v2 error. */
@RequiredArgsConstructor
public class PrimaryAssistant {
    public Map<String, Object> profile(AuthenticatedUser user, String authorization) {
        session(user, authorization);
        var profile = ports.properties().getProfile();
        return Map.of("engine", "spring-ai-2", "enabled", enabled(), "profile", profile.name(), "automaticCorrections", profile.automaticCorrections(),
                "modelJudge", profile.modelJudge(), "projectionVersion", com.voltcanary.aiagent.orchestration.routing.StrictOutputProjection.VERSION,
                "graphEngine", ports.runtime().graphEngine(), "graphDefinition", ports.runtime().graphDefinition(),
                "sourceMappingEnabled", enabled() && ports.properties().isNativeDataEnabled());
    }
    public record Ports(PrimaryRuntime runtime, PrimaryOrchestrationJournal journal, IntentPlanStore plans, ConversationStore conversations,
            AiAgentOrchestrator reviews, AiModelResolver models, AiModelConfigRepository configs, AuthorizedPlanningContext authority,
            ObjectMapper mapper, PrimaryAssistantProperties properties, LegacyAssistantContinuation legacy) { }
    private final Ports ports;

    public boolean enabled() { return ports.properties().isEnabled(); }
    private void requireEnabled() {
        if (!enabled()) throw new IntentPlans.Rejected(503, "ASSISTANT_GENERATION_DISABLED");
    }
    @AssistantBoundary(operation = AssistantBoundary.Operation.PREPARE)
    public PlanResponse create(AuthenticatedUser user, com.voltcanary.aiagent.dto.PlanRequest request, String authorization) {
        requireEnabled();
        var session = session(user, authorization);
        UUID requestId = request.requestId() == null ? UUID.randomUUID() : request.requestId();
        UUID conversation = UUID.nameUUIDFromBytes(("v2-plan:" + session.owner() + ":" + requestId).getBytes(StandardCharsets.UTF_8));
        ports.conversations().create(session.owner(), conversation);
        return submit(user, conversation, new ConversationContracts.Submit(requestId, 0, request.message(), request.context(), request.baseReviewId(),
                request.providerId(), request.modelConfigId(), request.metadata(), false, null), authorization).plan();
    }
    @AssistantBoundary(operation = AssistantBoundary.Operation.PREPARE)
    public PlanResponse clarify(AuthenticatedUser user, UUID parent, String message, com.voltcanary.aiagent.dto.DashboardTurnMetadata metadata, String authorization) {
        var session = session(user, authorization);
        var binding = ports.journal().review(session.owner(), parent);
        IntentPlans.State state;
        if (binding.isPresent()) state = ports.plans().read(session.owner(), binding.get().planId());
        else {
            try { state = ports.plans().read(session.owner(), parent); }
            catch (IntentPlans.Rejected missing) {
                if (missing.status() != 404) throw missing;
                var review = ports.reviews().getPlan(user, parent, authorization);
                requireEnabled();
                UUID conversation = ports.legacy().prepare(session.owner(), parent);
                UUID requestId = metadata == null || metadata.requestId() == null ? UUID.randomUUID() : metadata.requestId();
                var prior = ports.journal().turn(session.owner(), conversation, requestId);
                long revision = prior.isPresent() && prior.get().clientRevision() != null ? prior.get().clientRevision()
                        : ports.conversations().get(session.owner(), conversation).revision();
                return submit(user, conversation, new ConversationContracts.Submit(requestId, revision, message, Map.of(), parent,
                        review.providerId(), review.modelConfigId(), metadata, false, null), authorization).plan();
            }
        }
        UUID requestId = metadata == null || metadata.requestId() == null ? UUID.randomUUID() : metadata.requestId();
        var old = ports.journal().turn(session.owner(), state.conversationId(), requestId);
        long revision = old.isPresent() && old.get().clientRevision() != null ? old.get().clientRevision() : ports.conversations().get(session.owner(), state.conversationId()).revision();
        return submit(user, state.conversationId(), new ConversationContracts.Submit(requestId, revision, message, Map.of(), binding.isPresent() ? parent : null,
                null, null, metadata, false, null), authorization).plan();
    }
    public java.util.Optional<PlanResponse> readPlan(AuthenticatedUser user, UUID id, String authorization) {
        var session = session(user, authorization);
        try { return java.util.Optional.of(detail(user, ports.plans().read(session.owner(), id).conversationId(), authorization).plan()); }
        catch (IntentPlans.Rejected missing) { if (missing.status() != 404) throw missing; return java.util.Optional.empty(); }
    }
    public OrchestrationPorts.Session session(AuthenticatedUser user, String authorization) {
        return new OrchestrationPorts.Session(user, ports.authority().scope(user, authorization), authorization);
    }
    public boolean hasConversation(AuthenticatedUser user, UUID conversation, String authorization) {
        return ports.journal().current(session(user, authorization).owner(), conversation).isPresent();
    }
    @AssistantBoundary(operation = AssistantBoundary.Operation.SUBMIT)
    public ConversationContracts.Detail submit(AuthenticatedUser user, UUID conversation, ConversationContracts.Submit unsafe, String authorization) {
        requireEnabled();
        var session = session(user, authorization);
        var request = ConversationRedaction.value(unsafe, ConversationContracts.Submit.class, ports.mapper());
        if (request.automatic()) throw IntentPlans.conflict("V2_REQUIRES_EXPLICIT_USER_INPUT");
        var old = ports.journal().turn(session.owner(), conversation, request.requestId());
        if (old.isEmpty() && !user.expiresAfter(java.time.Instant.now().plusSeconds(300)))
            throw new IntentPlans.Rejected(401, "ASSISTANT_SESSION_REFRESH_REQUIRED");
        var selectedReview = request.parentPlanId();
        if (request.metadata() != null && request.metadata().baseReviewId() != null) selectedReview = request.metadata().baseReviewId();
        var state = old.isPresent() ? ports.plans().read(session.owner(), old.get().planId()) : ports.journal().current(session.owner(), conversation).orElse(null);
        if (selectedReview != null && state != null && selectedReview.equals(state.planId())) {
            String acceptedId = old.isPresent() ? old.get().request().selectedReviewId() : state.focus().reviewId();
            selectedReview = acceptedId == null ? null : UUID.fromString(acceptedId);
        }
        if (selectedReview != null) ports.reviews().getPlan(user, selectedReview, authorization);
        if (old.isEmpty() && state != null && List.of("SUCCEEDED", "CANCELLED", "EXPIRED").contains(state.status())) state = null;
        UUID modelId = request.modelConfigId();
        if (state != null && state.policyId().startsWith("primary-")) {
            UUID pinned = UUID.fromString(state.policyId().substring("primary-".length()));
            if (modelId != null && !modelId.equals(pinned)) throw IntentPlans.conflict("MODEL_CHANGE_REQUIRES_NEW_PLAN");
            modelId = pinned;
        }
        var selected = resolve(user, request.providerId(), modelId);
        var engine = ports.runtime().engine(session, selected);
        var selection = new ArrayList<AssistantProtocol.TurnSubmitSelectionItem>();
        var supplied = request.context() == null ? Map.<String, Object>of() : request.context();
        if (supplied.containsKey("vehiclePoint")) selection.add(new AssistantProtocol.TurnSubmitSelectionItem("vehiclePoint",
                com.voltcanary.aiagent.vehicle.VehicleMonitoringPoint.selection(supplied.get("vehiclePoint"))));
        if (supplied.containsKey("sourceBinding")) {
            if (!(supplied.get("sourceBinding") instanceof String binding)
                    || !binding.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}:[1-9][0-9]{0,15}")
                    || Long.parseLong(binding.substring(37)) > 9_007_199_254_740_991L) {
                throw IntentPlans.reject("SOURCE_CAPTURE_SELECTION_INVALID");
            }
            selection.add(new AssistantProtocol.TurnSubmitSelectionItem("sourceBinding", binding));
        }
        if (supplied.get("deviceId") instanceof String id) selection.add(new AssistantProtocol.TurnSubmitSelectionItem("deviceId", UUID.fromString(id).toString()));
        if (supplied.get("deviceIds") instanceof List<?> ids) for (var id : ids) selection.add(new AssistantProtocol.TurnSubmitSelectionItem("deviceId", UUID.fromString(id.toString()).toString()));
        if (supplied.containsKey("knowledgeCorpusRefs")) {
            if (!(supplied.get("knowledgeCorpusRefs") instanceof List<?> refs) || refs.size() > 32) {
                throw IntentPlans.reject("INVALID_KNOWLEDGE_CORPUS_SCOPE");
            }
            try {
                if (refs.isEmpty()) selection.add(new AssistantProtocol.TurnSubmitSelectionItem("knowledgeCorpusScope", "CLEAR"));
                refs.stream().map(ref -> UUID.fromString(String.valueOf(ref)).toString()).distinct()
                        .forEach(ref -> selection.add(new AssistantProtocol.TurnSubmitSelectionItem("knowledgeCorpusRef", ref)));
            } catch (IllegalArgumentException invalid) {
                throw IntentPlans.reject("INVALID_KNOWLEDGE_CORPUS_SCOPE");
            }
        }
        for (String field : List.of("locationId", "sourceId", "name")) if (supplied.get(field) instanceof String value) selection.add(new AssistantProtocol.TurnSubmitSelectionItem(field, value));
        selection.sort(Comparator.comparing(AssistantProtocol.TurnSubmitSelectionItem::field).thenComparing(AssistantProtocol.TurnSubmitSelectionItem::ref));
        var canonical = new AssistantProtocol.TurnSubmit("assistant-v2", "turn.submit", request.requestId().toString(),
                old.isPresent() ? old.get().request().expectedRevision() : state == null ? 0L : state.revision(), request.text(), selectedReview == null ? null : selectedReview.toString(),
                selection, new AssistantProtocol.RoutingPreference("PINNED", engine.policyId(), selected.modelConfig().getId().toString()),
                old.isPresent() ? old.get().request().replyToQuestionId() : state == null || state.pendingQuestion() == null ? null : state.pendingQuestion().questionId(),
                request.continueStepId());
        if (old.isPresent() && old.get().clientRevision() != null && old.get().clientRevision() != request.expectedRevision()) throw IntentPlans.conflict("REQUEST_ID_CONVERSATION_REVISION_CHANGED");
        engine.turns().submit(session, conversation, state == null ? null : state.planId(), canonical, engine.policyId(), engine.policyVersion(), engine.skills(), request.expectedRevision());
        return detail(user, conversation, authorization);
    }
    private AiResolvedModel resolve(AuthenticatedUser user, UUID provider, UUID model) {
        if (provider == null && model != null) provider = ports.configs().findById(model).orElseThrow(() -> IntentPlans.reject("MODEL_UNAVAILABLE")).getProviderId();
        return ports.models().resolve(user, provider, model).orElseThrow(() -> IntentPlans.reject("CONFIGURED_MODEL_REQUIRED"));
    }
    public ConversationContracts.Detail detail(AuthenticatedUser user, UUID conversation, String authorization) {
        var session = session(user, authorization);
        var state = ports.journal().current(session.owner(), conversation).orElseThrow(() -> new IntentPlans.Rejected(404, "ORCHESTRATION_UNAVAILABLE"));
        PlanResponse response = null;
        if (state.focus().reviewId() != null) response = ports.reviews().getPlan(user, UUID.fromString(state.focus().reviewId()), authorization);
        var accepted = response;
        String requestStatus = ports.conversations().pending(session.owner(), conversation);
        boolean interruptedOrPending = "PENDING".equals(requestStatus) || "UNKNOWN".equals(requestStatus);
        if (interruptedOrPending) response = null;
        else if (state.pendingQuestion() != null || state.status().equals("FAILED") || response == null) response = question(state);
        else {
            var value = response;
            var history = ports.journal().history(session.owner(), state.planId()).stream().filter(id -> !id.equals(value.planId())).toList();
            response = new PlanResponse(value.planId(), value.summary(), value.explanation(), value.confirmationRequired(), value.safetyNotes(), value.actions(),
                    value.providerType(), value.providerName(), value.modelId(), value.providerId(), value.modelConfigId(), value.fallbackUsed(), value.version(), value.payloadDigest(),
                    value.expiresAt(), value.planningStatus(), value.questions(), value.executionStatus(), value.execution(), value.clarification(), history, value.task(), value.restoredFromReviewId());
        }
        var wire = ports.mapper().createObjectNode().put("version", "assistant-v2").put("planId", state.planId().toString()).put("revision", state.revision()).put("status", state.status());
        wire.set("steps", ports.mapper().valueToTree(state.steps()));
        wire.set("pendingQuestion", ports.mapper().valueToTree(state.pendingQuestion()));
        wire.set("focus", ports.mapper().valueToTree(state.focus()));
        return new ConversationContracts.Detail(ports.conversations().get(session.owner(), conversation), response, "AVAILABLE",
                interruptedOrPending ? requestStatus : state.status().equals("FAILED") ? "FAILED" : "COMPLETED", wire,
                accepted != null && (response == null || !response.planId().equals(accepted.planId())) ? accepted : null);
    }
    private PlanResponse question(IntentPlans.State state) {
        String text = state.status().equals("FAILED") ? "Не удалось подготовить запрос. История и подтверждённые результаты сохранены." : state.pendingQuestion() == null ? "Продолжите следующий шаг плана." : state.pendingQuestion().question().text();
        var question = state.pendingQuestion() == null || state.status().equals("FAILED") ? null : new PlanningQuestion(state.pendingQuestion().question().missingFields(), "", "Ответить вручную", Map.of(), true);
        String code = state.steps().stream().filter(step -> step.diagnostic() != null).map(step -> step.diagnostic().code()).findFirst().orElse(state.status());
        var selected = state.policyId().startsWith("primary-") ? ports.configs().findById(UUID.fromString(state.policyId().substring("primary-".length()))).orElse(null) : null;
        return new PlanResponse(state.planId(), "assistant-v2", text, false, List.of("assistant-v2", code), List.of(), null, null,
                selected == null ? null : selected.getModelId(), selected == null ? null : selected.getProviderId(), selected == null ? null : selected.getId(), false,
                0, null, state.expiresAt(), state.status().equals("FAILED") ? "FAILED" : "NEEDS_INPUT", state.pendingQuestion() == null ? List.of() : List.of(text),
                state.status(), null, question, List.of(), null, null);
    }
    public boolean mapped(AuthenticatedUser user, UUID review, String authorization) {
        return ports.journal().review(session(user, authorization).owner(), review).isPresent();
    }
    @AssistantBoundary(operation = AssistantBoundary.Operation.EXECUTE)
    public ExecutePlanResponse execute(AuthenticatedUser user, UUID review, boolean confirmed, Integer version, String digest, String authorization) {
        if (!confirmed) throw IntentPlans.conflict("CONFIRMATION_REQUIRED");
        var session = session(user, authorization);
        var binding = ports.journal().review(session.owner(), review).orElseThrow(() -> IntentPlans.reject("REVIEW_UNAVAILABLE"));
        var state = ports.plans().read(session.owner(), binding.planId());
        UUID requestId = UUID.nameUUIDFromBytes(("confirm:" + review + ":" + version + ":" + digest).getBytes(StandardCharsets.UTF_8));
        long revision = state.revision();
        try { revision = ports.mapper().readTree(ports.plans().request(session.owner(), state.planId(), requestId)).path("expectedRevision").asLong(); }
        catch (IntentPlans.Rejected missing) { if (missing.status() != 404) throw missing; }
        catch (java.io.IOException invalid) { throw IntentPlans.reject("SAVED_CONFIRMATION_INVALID"); }
        var request = new AssistantProtocol.ConfirmationRequest("assistant-v2", "confirmation.request", requestId.toString(), binding.stepId(), review.toString(), revision, version == null ? -1L : version.longValue(), digest);
        ports.runtime().actions().confirm(session, state.planId(), request);
        var actual = ports.reviews().getPlan(user, review, authorization);
        return actual.execution() == null ? new ExecutePlanResponse(review, actual.executionStatus(), List.of()) : actual.execution();
    }
    @AssistantBoundary(operation = AssistantBoundary.Operation.CONTROL)
    public IntentPlans.State control(AuthenticatedUser user, UUID plan, AssistantProtocol.ControlRequest request, String authorization) {
        var session = session(user, authorization);
        return ports.runtime().actions().control(session, plan, request);
    }
}
