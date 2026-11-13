package com.voltcanary.aiagent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.aiagent.config.AiTaskProperties;
import com.voltcanary.aiagent.domain.DashboardTaskState;
import com.voltcanary.aiagent.domain.DashboardTurn;
import com.voltcanary.aiagent.dto.PlanRequest;
import com.voltcanary.aiagent.dto.PlanResponse;
import com.voltcanary.aiagent.dto.DashboardTurnMetadata;
import com.voltcanary.aiagent.model.AgentPlan;
import com.voltcanary.aiagent.port.DashboardTasks;
import com.voltcanary.aiagent.security.AuthenticatedUser;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class DashboardTaskConversationTest {
    private final DashboardTasks tasks = mock(DashboardTasks.class);
    private final AiAgentOrchestrator orchestrator = mock(AiAgentOrchestrator.class);
    private final AuthorizedPlanningContext contexts = mock(AuthorizedPlanningContext.class);
    private final PlanClarifications clarifications = mock(PlanClarifications.class);
    private final DashboardPlanningContext routing = mock(DashboardPlanningContext.class);
    private final AuthenticatedUser user = new AuthenticatedUser(UUID.randomUUID(), "qa@example.invalid", "USER");
    private final AuthorizedPlanningContext.Scope scope = new AuthorizedPlanningContext.Scope(user.userId(), UUID.randomUUID());
    private final AtomicReference<DashboardTaskState> persisted = new AtomicReference<>();
    private final AtomicReference<DashboardTurn> recorded = new AtomicReference<>();
    private DashboardTaskConversation conversation;

    @BeforeEach
    void setup() {
        when(contexts.scope(user, "auth")).thenReturn(scope);
        when(routing.requested(anyString())).thenReturn(true);
        when(tasks.request(any(), any(), any())).thenAnswer(call -> Optional.ofNullable(recorded.get())
                .filter(turn -> turn.requestId().equals(call.getArgument(2))));
        when(tasks.accept(any(), any(), any(), any(Boolean.class))).thenAnswer(call -> {
            DashboardTaskState state = call.getArgument(0);
            DashboardTurn turn = call.getArgument(1);
            var next = state.begin(turn.turnId(), turn.message());
            persisted.set(next);
            return next;
        });
        doAnswer(call -> { persisted.set(call.getArgument(0)); return null; }).when(tasks).checkpoint(any());
        doAnswer(call -> { persisted.set(call.getArgument(0)); recorded.set(call.getArgument(1)); return null; }).when(tasks).finish(any(), any());
        when(tasks.find(any(), any(), any(), any())).thenAnswer(call -> persisted.get());
        conversation = new DashboardTaskConversation(tasks, orchestrator, contexts, clarifications, routing,
                new PlanningInputGuard(), new AiTaskProperties(), new ObjectMapper());
    }

    @Test
    void nameIsCheckpointedBeforeAProviderFailureAndDuplicateDeliveryDoesNotCallItAgain() {
        var request = request("Назови Мой любимый трамвай", UUID.randomUUID());
        when(orchestrator.createPlan(eq(user), anyString(), anyMap(), isNull(), isNull(), eq("auth"), any())).thenAnswer(call -> {
            assertThat(persisted.get().name().value()).isEqualTo("Мой любимый трамвай");
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "provider unavailable");
        });
        assertThatThrownBy(() -> conversation.create(user, request, "auth")).isInstanceOf(ResponseStatusException.class);
        assertThat(persisted.get().status()).isEqualTo("FAILED");
        assertThat(persisted.get().lastAcceptedReviewId()).isNull();
        assertThat(persisted.get().pending().getFirst().request()).isEqualTo(request.message());
        assertThatThrownBy(() -> conversation.create(user, request, "auth"))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> assertThat(error.getStatusCode().value()).isEqualTo(502));
        verify(orchestrator).createPlan(eq(user), anyString(), anyMap(), isNull(), isNull(), eq("auth"), any());
    }

    @Test
    void modelNameGroundedInTheUserTurnSurvivesPreparationFailure() {
        var request = request("Мой любимый трамвай", UUID.randomUUID());
        when(orchestrator.createPlan(eq(user), anyString(), anyMap(), isNull(), isNull(), eq("auth"), any())).thenAnswer(call -> {
            Consumer<String> checkpoint = call.getArgument(6);
            checkpoint.accept("Мой любимый трамвай");
            assertThat(persisted.get().name().value()).isEqualTo("Мой любимый трамвай");
            return response("NEEDS_INPUT", "Не удалось подготовить изменения");
        });
        var response = conversation.create(user, request, "auth");
        assertThat(response.task().name()).isEqualTo("Мой любимый трамвай");
        assertThat(response.task().failureCategory()).isEqualTo("PREPARATION_FAILED");
        assertThat(response.task().lastAcceptedReviewId()).isNull();
    }

    @Test
    void proposedNameDoesNotBecomeAnExplicitFactAndReadyCandidateDoesNotClearPendingGoals() {
        var request = request("Создай дашборд с картой", UUID.randomUUID());
        when(orchestrator.createPlan(eq(user), anyString(), anyMap(), isNull(), isNull(), eq("auth"), any())).thenAnswer(call -> {
            Consumer<String> checkpoint = call.getArgument(6);
            checkpoint.accept("Invented model name");
            return response("READY", "Preview");
        });
        var response = conversation.create(user, request, "auth");
        assertThat(response.task().name()).isNull();
        assertThat(response.task().lastAcceptedReviewId()).isEqualTo(response.planId());
        assertThat(response.task().pending()).allMatch(change -> change.disposition() == DashboardTaskState.Disposition.PENDING);
    }

    @Test
    void sameRequestIdentityWithAnotherPayloadConflictsBeforeAnotherProviderCall() {
        var first = request("Создай дашборд с картой", UUID.randomUUID());
        var ready = response("READY", "Preview");
        when(orchestrator.createPlan(eq(user), anyString(), anyMap(), isNull(), isNull(), eq("auth"), any())).thenReturn(ready);
        conversation.create(user, first, "auth");
        var changed = new PlanRequest("Создай другой дашборд", Map.of(), null, null, first.requestId(), first.taskId(), first.branchId(), 0L, null);
        assertThatThrownBy(() -> conversation.create(user, changed, "auth"))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> assertThat(error.getStatusCode().value()).isEqualTo(409));
        verify(orchestrator).createPlan(eq(user), anyString(), anyMap(), isNull(), isNull(), eq("auth"), any());
    }

    @Test
    void responseLossReplaysTheExactReview() {
        var request = request("Создай дашборд с картой", UUID.randomUUID());
        var ready = response("READY", "Preview");
        when(orchestrator.createPlan(eq(user), anyString(), anyMap(), isNull(), isNull(), eq("auth"), any())).thenReturn(ready);
        var original = conversation.create(user, request, "auth");
        when(orchestrator.getPlan(user, ready.planId(), "auth")).thenReturn(ready);
        assertThat(conversation.create(user, request, "auth")).isEqualTo(original);
        verify(orchestrator).createPlan(eq(user), anyString(), anyMap(), isNull(), isNull(), eq("auth"), any());
    }

    @Test
    void recoveryBeforeAnyReviewDoesNotInvokePlanningOrInventOne() {
        var request = request("Создай дашборд с картой", UUID.randomUUID());
        when(orchestrator.createPlan(eq(user), anyString(), anyMap(), isNull(), isNull(), eq("auth"), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY));
        assertThatThrownBy(() -> conversation.create(user, request, "auth")).isInstanceOf(ResponseStatusException.class);
        var recovered = conversation.recover(user, request.taskId(), request.branchId(), "auth");
        assertThat(recovered.lastReview()).isNull();
        assertThat(recovered.task().status()).isEqualTo("FAILED");
        verify(orchestrator, never()).getPlan(any(), any(), any());
    }

    @Test
    void retryOfAnInitialFailureContinuationCanReplayItsNowReadyReview() {
        var initial = request("Создай дашборд", UUID.randomUUID());
        when(orchestrator.createPlan(eq(user), anyString(), anyMap(), isNull(), isNull(), eq("auth"), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY));
        assertThatThrownBy(() -> conversation.create(user, initial, "auth")).isInstanceOf(ResponseStatusException.class);
        var next = new PlanRequest("Попробуй иначе", Map.of(), null, null, UUID.randomUUID(), initial.taskId(), initial.branchId(), 1L, null);
        var ready = response("READY", "Preview");
        when(orchestrator.createPlan(eq(user), anyString(), anyMap(), isNull(), isNull(), eq("auth"), any())).thenReturn(ready);
        var result = conversation.create(user, next, "auth");
        when(orchestrator.getPlan(user, ready.planId(), "auth")).thenReturn(ready);
        assertThat(conversation.create(user, next, "auth")).isEqualTo(result);
    }

    @Test
    void recordedClarificationIsReadWithoutReopeningItsNowExecutedParent() {
        var initial = request("Создай дашборд", UUID.randomUUID());
        var ready = response("READY", "Preview");
        when(orchestrator.createPlan(eq(user), anyString(), anyMap(), isNull(), isNull(), eq("auth"), any())).thenReturn(ready);
        conversation.create(user, initial, "auth");
        var root = recorded.get();
        when(tasks.review(scope.userId(), scope.accountId(), ready.planId())).thenReturn(Optional.of(root));
        var parent = new AgentPlan(ready.planId(), user.userId(), initial.message(), "Preview", "Preview", List.of(), List.of(), Instant.now());
        var stored = new PlanStore.Stored(parent, scope.accountId(), 1, "digest", Instant.now().plusSeconds(3600), "READY", null, List.of());
        var input = Map.<String, Object>of("originalDashboardRequest", initial.message());
        when(clarifications.prepare(user, ready.planId(), "Назови Мой трамвай", "auth"))
                .thenReturn(new PlanClarifications.Continuation(stored, initial.message(), input, 1, List.of("Назови Мой трамвай")))
                .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "Parent executed"));
        var child = response("READY", "Updated");
        when(orchestrator.clarify(eq(user), eq(ready.planId()), anyString(), eq("auth"), anyMap(), any())).thenReturn(child);
        var metadata = new DashboardTurnMetadata(UUID.randomUUID(), initial.taskId(), initial.branchId(), 1L, ready.planId(), false);
        var result = conversation.clarify(user, ready.planId(), "Назови Мой трамвай", metadata, "auth");
        when(orchestrator.getPlan(user, child.planId(), "auth")).thenReturn(child);
        assertThat(conversation.clarify(user, ready.planId(), "Назови Мой трамвай", metadata, "auth")).isEqualTo(result);
        verify(clarifications).prepare(user, ready.planId(), "Назови Мой трамвай", "auth");
    }

    @Test
    void labelledAcceptedDefaultIsSavedWithItsProvenanceBeforeProviderFailure() {
        var initial = request("Создай дашборд", UUID.randomUUID());
        var ready = response("READY", "Preview");
        when(orchestrator.createPlan(eq(user), anyString(), anyMap(), isNull(), isNull(), eq("auth"), any())).thenReturn(ready);
        conversation.create(user, initial, "auth");
        link(ready, initial.message(), "Да", Map.of("priorSuggestedValues", Map.of("name", "Предложенное имя")));
        when(orchestrator.clarify(eq(user), eq(ready.planId()), anyString(), eq("auth"), anyMap(), any())).thenAnswer(call -> {
            assertThat(persisted.get().name().value()).isEqualTo("Предложенное имя");
            assertThat(persisted.get().name().provenance()).isEqualTo(DashboardTaskState.Provenance.USER_ACCEPTED_DEFAULT);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY);
        });
        var metadata = new DashboardTurnMetadata(UUID.randomUUID(), initial.taskId(), initial.branchId(), 1L, ready.planId(), true);
        assertThatThrownBy(() -> conversation.clarify(user, ready.planId(), "Да", metadata, "auth")).isInstanceOf(ResponseStatusException.class);
        assertThat(persisted.get().name().value()).isEqualTo("Предложенное имя");
        assertThat(persisted.get().lastAcceptedReviewId()).isEqualTo(ready.planId());
    }

    @Test
    void explicitNameOverridesConflictingSuggestionAndModelOutput() {
        var initial = request("Создай дашборд", UUID.randomUUID());
        var ready = response("READY", "Preview");
        when(orchestrator.createPlan(eq(user), anyString(), anyMap(), isNull(), isNull(), eq("auth"), any())).thenReturn(ready);
        conversation.create(user, initial, "auth");
        String answer = "Назови Моё явное имя";
        link(ready, initial.message(), answer, Map.of("priorSuggestedValues", Map.of("name", "Предложенное имя")));
        when(orchestrator.clarify(eq(user), eq(ready.planId()), anyString(), eq("auth"), anyMap(), any())).thenAnswer(call -> {
            Consumer<String> checkpoint = call.getArgument(5);
            checkpoint.accept("Предложенное имя");
            return response("NEEDS_INPUT", "Не удалось подготовить изменения");
        });
        var metadata = new DashboardTurnMetadata(UUID.randomUUID(), initial.taskId(), initial.branchId(), 1L, ready.planId(), true);
        conversation.clarify(user, ready.planId(), answer, metadata, "auth");
        assertThat(persisted.get().name().value()).isEqualTo("Моё явное имя");
        assertThat(persisted.get().name().provenance()).isEqualTo(DashboardTaskState.Provenance.USER_EXPLICIT);
    }

    @Test
    void aFailedNameReplyContinuesTheCurrentTaskRatherThanForkingItsOldQuestion() {
        var initial = request("Создай дашборд", UUID.randomUUID());
        var question = response("NEEDS_INPUT", "Как назвать?");
        when(orchestrator.createPlan(eq(user), anyString(), anyMap(), isNull(), isNull(), eq("auth"), any())).thenReturn(question);
        conversation.create(user, initial, "auth");
        var questionTurn = recorded.get();
        when(tasks.review(scope.userId(), scope.accountId(), question.planId())).thenReturn(Optional.of(questionTurn));
        var parent = new AgentPlan(question.planId(), user.userId(), initial.message(), "Имя", "Имя?", List.of(), List.of(), Instant.now());
        var stored = new PlanStore.Stored(parent, scope.accountId(), 1, "digest", Instant.now().plusSeconds(3600), "NEEDS_INPUT", null, List.of());
        when(clarifications.prepare(eq(user), eq(question.planId()), anyString(), eq("auth"))).thenAnswer(call ->
                new PlanClarifications.Continuation(stored, initial.message(), Map.of(), 1, List.of(call.<String>getArgument(2))));
        when(orchestrator.clarify(eq(user), eq(question.planId()), anyString(), eq("auth"), anyMap(), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY));
        var first = new DashboardTurnMetadata(UUID.randomUUID(), initial.taskId(), initial.branchId(), 1L, question.planId(), false);
        assertThatThrownBy(() -> conversation.clarify(user, question.planId(), "Назови Мой трамвай", first, "auth"))
                .isInstanceOf(ResponseStatusException.class);
        var ready = response("READY", "Prepared");
        when(orchestrator.clarify(eq(user), eq(question.planId()), anyString(), eq("auth"), anyMap(), any())).thenAnswer(call -> {
            assertThat(((Map<?, ?>) call.getArgument(4)).get("name")).isEqualTo("Мой трамвай");
            return ready;
        });
        var retry = new DashboardTurnMetadata(UUID.randomUUID(), initial.taskId(), initial.branchId(), 2L, question.planId(), false);
        var result = conversation.clarify(user, question.planId(), "Продолжай", retry, "auth");
        assertThat(result.task().branchId()).isEqualTo(initial.branchId());
        assertThat(result.task().revision()).isEqualTo(3);
        assertThat(result.task().name()).isEqualTo("Мой трамвай");
    }

    @Test void checkpointsGroundedSourceChoiceBeforeAnotherProviderFailure() {
        var initial = request("Создай дашборд", UUID.randomUUID());
        var question = response("NEEDS_INPUT", "Choose source");
        when(orchestrator.createPlan(eq(user), anyString(), anyMap(), isNull(), isNull(), eq("auth"), any())).thenReturn(question);
        conversation.create(user, initial, "auth");
        String source = UUID.randomUUID().toString();
        link(question, initial.message(), "GPS B", Map.of("sourceId", source, "sourceSelectionReply", true));
        when(orchestrator.clarify(eq(user), eq(question.planId()), anyString(), eq("auth"), anyMap(), any())).thenAnswer(call -> {
            assertThat(persisted.get().selection().sourceId()).isEqualTo(source);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY);
        });
        var metadata = new DashboardTurnMetadata(UUID.randomUUID(), initial.taskId(), initial.branchId(), 1L, question.planId(), false);
        assertThatThrownBy(() -> conversation.clarify(user, question.planId(), "GPS B", metadata, "auth")).isInstanceOf(ResponseStatusException.class);
        assertThat(persisted.get().selection().sourceId()).isEqualTo(source);
        assertThat(persisted.get().status()).isEqualTo("FAILED");
    }

    @Test void aGroundedRenamingOperationUpdatesTheKnownFactBeforePreparationFailure() {
        var initial = request("Назови Старое имя", UUID.randomUUID());
        var ready = response("READY", "Preview");
        when(orchestrator.createPlan(eq(user), anyString(), anyMap(), isNull(), isNull(), eq("auth"), any())).thenReturn(ready);
        conversation.create(user, initial, "auth");
        String answer = "Измени название на «Городской трамвай»";
        link(ready, initial.message(), answer, Map.of());
        when(orchestrator.clarify(eq(user), eq(ready.planId()), anyString(), eq("auth"), anyMap(), any())).thenAnswer(call -> {
            Consumer<String> checkpoint = call.getArgument(5);
            checkpoint.accept("Городской трамвай");
            return response("NEEDS_INPUT", "Не удалось подготовить изменения");
        });
        var metadata = new DashboardTurnMetadata(UUID.randomUUID(), initial.taskId(), initial.branchId(), 1L, ready.planId(), false);
        var result = conversation.clarify(user, ready.planId(), answer, metadata, "auth");
        assertThat(result.task().name()).isEqualTo("Городской трамвай");
        assertThat(persisted.get().name().provenance()).isEqualTo(DashboardTaskState.Provenance.USER_EXPLICIT);
        assertThat(persisted.get().status()).isEqualTo("FAILED");
    }

    private void link(PlanResponse ready, String original, String answer, Map<String, Object> input) {
        when(tasks.review(scope.userId(), scope.accountId(), ready.planId())).thenReturn(Optional.of(recorded.get()));
        var parent = new AgentPlan(ready.planId(), user.userId(), original, "Preview", "Preview", List.of(), List.of(), Instant.now());
        var stored = new PlanStore.Stored(parent, scope.accountId(), 1, "digest", Instant.now().plusSeconds(3600), "READY", null, List.of());
        when(clarifications.prepare(user, ready.planId(), answer, "auth"))
                .thenReturn(new PlanClarifications.Continuation(stored, original, input, 1, List.of(answer)));
    }

    private PlanRequest request(String message, UUID requestId) {
        UUID id = UUID.randomUUID();
        return new PlanRequest(message, Map.of(), null, null, requestId, id, id, 0L, null);
    }

    private PlanResponse response(String status, String summary) {
        return new PlanResponse(UUID.randomUUID(), summary, "explanation", false, List.of(), List.of(),
                "OPENAI", "test", "test", null, null, false, 1, "digest", Instant.now().plusSeconds(3600),
                status, List.of(), "NEEDS_INPUT", null, null, List.of(), null, null);
    }
}
