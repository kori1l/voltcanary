package com.voltcanary.aiagent;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.aiagent.adapter.PrimaryOrchestrationJournal;
import com.voltcanary.aiagent.config.PrimaryAssistantProperties;
import com.voltcanary.aiagent.conversation.ConversationContracts;
import com.voltcanary.aiagent.conversation.ConversationStore;
import com.voltcanary.aiagent.dto.ExecutePlanResponse;
import com.voltcanary.aiagent.dto.PlanResponse;
import com.voltcanary.aiagent.llm.adapter.AiModelResolver;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol;
import com.voltcanary.aiagent.orchestration.plan.IntentPlanActions;
import com.voltcanary.aiagent.orchestration.plan.IntentPlanStore;
import com.voltcanary.aiagent.orchestration.plan.IntentPlans;
import com.voltcanary.aiagent.orchestration.plan.OrchestrationPorts;
import com.voltcanary.aiagent.security.AuthenticatedUser;
import com.voltcanary.aiagent.service.AiAgentOrchestrator;
import com.voltcanary.aiagent.service.AuthorizedPlanningContext;
import com.voltcanary.aiagent.service.PrimaryAssistant;
import com.voltcanary.aiagent.service.PrimaryRuntime;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlanningSessionLifetimeTest {

    @Test
    void conversationHttpSubmissionPreservesExplicitKnowledgeSelectionAndClear() throws Exception {
        var user = new AuthenticatedUser(
            UUID.randomUUID(),
            "fixture@example.test",
            "USER",
            Instant.now().plusSeconds(900)
        );
        var owner = new AuthorizedPlanningContext.Scope(user.userId(), UUID.randomUUID());
        var authority = mock(AuthorizedPlanningContext.class);
        when(authority.scope(user, "Bearer fixture")).thenReturn(owner);
        var journal = mock(PrimaryOrchestrationJournal.class);
        var models = mock(AiModelResolver.class);
        var runtime = mock(PrimaryRuntime.class);
        var turns = mock(com.voltcanary.aiagent.orchestration.plan.IntentOrchestration.class);
        var skills = new com.voltcanary.aiagent.orchestration.plan.IntentOrchestration.Skills(
            java.util.Set.of("CREATE_ALERT_RULE"),
            java.util.Set.of()
        );
        var model = new com.voltcanary.aiagent.domain.AiModelConfig();
        model.setId(UUID.randomUUID());
        var selected = new com.voltcanary.aiagent.llm.adapter.AiResolvedModel(
            null,
            model,
            "unused"
        );
        when(models.resolve(user, null, null)).thenReturn(Optional.of(selected));
        when(runtime.engine(any(), eq(selected))).thenReturn(
            new PrimaryRuntime.Engine(turns, null, skills, "policy", "1")
        );
        var properties = new PrimaryAssistantProperties();
        properties.setEnabled(true);
        var mapper = new ObjectMapper();
        var assistant = spy(
            new PrimaryAssistant(
                new PrimaryAssistant.Ports(
                    runtime,
                    journal,
                    null,
                    null,
                    null,
                    models,
                    null,
                    authority,
                    mapper,
                    properties,
                    null
                )
            )
        );
        var conversation = UUID.randomUUID();
        var detail = new ConversationContracts.Detail(null, null, null, null);
        doReturn(detail).when(assistant).detail(user, conversation, "Bearer fixture");
        var identity = mock(com.voltcanary.aiagent.security.JwtIdentityService.class);
        when(identity.parseBearerToken("Bearer fixture")).thenReturn(user);
        var controller = new com.voltcanary.aiagent.controller.AssistantConversationController(
            identity,
            null,
            null,
            authority,
            assistant
        );
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
            controller
        ).build();
        var corpus = UUID.randomUUID();
        var selections = java.util.List.of(
            Map.of("knowledgeCorpusRefs", java.util.List.of(corpus.toString(), corpus.toString())),
            Map.of("knowledgeCorpusRefs", java.util.List.<String>of()),
            Map.<String, java.util.List<String>>of()
        );
        for (var selection : selections) {
            var request = new ConversationContracts.Submit(
                UUID.randomUUID(),
                0,
                "Prepare an alert rule",
                new java.util.LinkedHashMap<>(selection),
                null,
                null,
                null,
                null,
                false,
                null
            );
            mvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/ai-agent/conversations/{id}/messages",
                    conversation
                )
                    .header("Authorization", "Bearer fixture")
                    .contentType("application/json")
                    .content(mapper.writeValueAsBytes(request))
            ).andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()
            );
        }
        var submitted = org.mockito.ArgumentCaptor.forClass(AssistantProtocol.TurnSubmit.class);
        verify(turns, times(3)).submit(
            any(),
            eq(conversation),
            isNull(),
            submitted.capture(),
            eq("policy"),
            eq("1"),
            eq(skills),
            eq(0L)
        );
        assertEquals(
            java.util.List.of(
                new AssistantProtocol.TurnSubmitSelectionItem(
                    "knowledgeCorpusRef",
                    corpus.toString()
                )
            ),
            submitted.getAllValues().getFirst().selection()
        );
        assertEquals(
            java.util.List.of(
                new AssistantProtocol.TurnSubmitSelectionItem("knowledgeCorpusScope", "CLEAR")
            ),
            submitted.getAllValues().get(1).selection()
        );
        assertTrue(submitted.getValue().selection().isEmpty());
    }

    @Test
    void disabledGenerationPreservesHistoryCancellationAndSavedReceipts() {
        var user = new AuthenticatedUser(UUID.randomUUID(), "fixture@example.test", "USER");
        String authorization = "Bearer fixture";
        var owner = new AuthorizedPlanningContext.Scope(user.userId(), UUID.randomUUID());
        var session = new OrchestrationPorts.Session(user, owner, authorization);
        var authority = mock(AuthorizedPlanningContext.class);
        when(authority.scope(user, authorization)).thenReturn(owner);
        var journal = mock(PrimaryOrchestrationJournal.class);
        var plans = mock(IntentPlanStore.class);
        var conversations = mock(ConversationStore.class);
        var reviews = mock(AiAgentOrchestrator.class);
        var runtime = mock(PrimaryRuntime.class);
        var actions = mock(IntentPlanActions.class);
        when(runtime.actions()).thenReturn(actions);
        var models = mock(AiModelResolver.class);
        var properties = new PrimaryAssistantProperties();
        properties.setEnabled(false);
        var assistant = new PrimaryAssistant(
            new PrimaryAssistant.Ports(
                runtime,
                journal,
                plans,
                conversations,
                reviews,
                models,
                null,
                authority,
                new ObjectMapper(),
                properties,
                null
            )
        );
        UUID conversation = UUID.randomUUID(),
            plan = UUID.randomUUID(),
            review = UUID.randomUUID();
        var state = mock(IntentPlans.State.class);
        when(state.planId()).thenReturn(plan);
        when(state.status()).thenReturn("SUCCEEDED");
        when(state.focus()).thenReturn(
            new AssistantProtocol.Focus(null, null, review.toString(), null, null)
        );
        when(journal.current(owner, conversation)).thenReturn(Optional.of(state));
        when(journal.review(owner, review)).thenReturn(
            Optional.of(new PrimaryOrchestrationJournal.Binding(plan, "device"))
        );
        when(plans.read(owner, plan)).thenReturn(state);
        when(plans.request(eq(owner), eq(plan), any())).thenThrow(
            new IntentPlans.Rejected(404, "missing")
        );
        var saved = mock(PlanResponse.class);
        when(saved.planId()).thenReturn(review);
        var receipt = new ExecutePlanResponse(review, "SUCCESS", java.util.List.of());
        when(saved.execution()).thenReturn(receipt);
        when(reviews.getPlan(user, review, authorization)).thenReturn(saved);

        var failure = assertThrows(IntentPlans.Rejected.class, () ->
            assistant.submit(user, conversation, null, authorization)
        );
        assertEquals("ASSISTANT_GENERATION_DISABLED", failure.getMessage());
        assertEquals(
            receipt,
            assistant.detail(user, conversation, authorization).plan().execution()
        );
        assertEquals(receipt, assistant.execute(user, review, true, 1, "digest", authorization));
        var cancel = new AssistantProtocol.ControlRequest(
            "assistant-v2",
            "control.request",
            UUID.randomUUID().toString(),
            0L,
            "CANCEL",
            null
        );
        when(actions.control(session, plan, cancel)).thenReturn(state);
        assertSame(state, assistant.control(user, plan, cancel, authorization));
        verify(actions).confirm(eq(session), eq(plan), any());
        verifyNoInteractions(models);
        verify(runtime, never()).engine(any(), any());
    }

    @Test
    void nearExpiryStopsBeforeModelResolutionOrTurnSubmission() {
        var user = new AuthenticatedUser(
            UUID.randomUUID(),
            "fixture@example.test",
            "USER",
            Instant.now().plusSeconds(20)
        );
        var authority = mock(AuthorizedPlanningContext.class);
        var scope = new AuthorizedPlanningContext.Scope(user.userId(), UUID.randomUUID());
        when(authority.scope(user, "Bearer fixture")).thenReturn(scope);
        var journal = mock(PrimaryOrchestrationJournal.class);
        when(journal.turn(any(), any(), any())).thenReturn(Optional.empty());
        var models = mock(AiModelResolver.class);
        var runtime = mock(PrimaryRuntime.class);
        var properties = new PrimaryAssistantProperties();
        properties.setEnabled(true);
        var assistant = new PrimaryAssistant(
            new PrimaryAssistant.Ports(
                runtime,
                journal,
                null,
                null,
                null,
                models,
                null,
                authority,
                new ObjectMapper(),
                properties,
                null
            )
        );
        var request = new ConversationContracts.Submit(
            UUID.randomUUID(),
            0,
            "Prepare a new device",
            Map.of(),
            null,
            null,
            null,
            null,
            false,
            null
        );
        var failure = assertThrows(IntentPlans.Rejected.class, () ->
            assistant.submit(user, UUID.randomUUID(), request, "Bearer fixture")
        );
        assertEquals(401, failure.status());
        assertEquals("ASSISTANT_SESSION_REFRESH_REQUIRED", failure.getMessage());
        verifyNoInteractions(models, runtime);
    }
}
