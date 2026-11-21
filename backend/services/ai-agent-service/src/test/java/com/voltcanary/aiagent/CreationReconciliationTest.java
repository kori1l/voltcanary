package com.voltcanary.aiagent;

import com.voltcanary.aiagent.dto.*;
import com.voltcanary.aiagent.model.*;
import com.voltcanary.aiagent.service.*;
import com.voltcanary.aiagent.audit.AuditLogger;
import com.voltcanary.aiagent.security.AuthenticatedUser;
import com.voltcanary.aiagent.port.AssistantTraceRecorder;
import com.voltcanary.aiagent.trace.AssistantTrace;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CreationReconciliationTest {
    @Test void requiredTraceStartBlocksDispatchAndOutcomeCaptureFailureDoesNotRepeatIt() {
        var actor = new AuthenticatedUser(UUID.randomUUID(), "fixture@example.invalid", "USER");
        var scope = new AuthorizedPlanningContext.Scope(actor.userId(), UUID.randomUUID());
        var action = new AgentAction(UUID.randomUUID(), AgentTool.CREATE_DEVICE, "Create", "Reviewed device", true,
                Map.of("name", "Reviewed", "type", "FRIDGE"));
        var plan = new AgentPlan(UUID.randomUUID(), actor.userId(), "Create", "Review", "Device",
                List.of(), List.of(action), Instant.now());
        var saved = new PlanStore.Stored(plan, scope.accountId(), 1, "digest", Instant.now().plusSeconds(3600),
                "READY", null, List.of());
        var contexts = mock(AuthorizedPlanningContext.class);
        when(contexts.scope(actor, "Bearer current")).thenReturn(scope);
        var store = mock(PlanStore.class);
        when(store.find(plan.id(), scope)).thenReturn(saved);
        when(store.claim(saved, scope, 1, "digest")).thenReturn(true);
        var executor = mock(ToolExecutor.class);
        var orchestrator = new AiAgentOrchestrator(mock(CreationTaskPlanning.class), store, executor,
                mock(AuditLogger.class), contexts, new CreationReconciliation(executor), mock(PlanClarifications.class));
        var correlation = new AssistantTraceRecorder.Correlation(scope, UUID.randomUUID(), plan.id(), UUID.randomUUID(), UUID.randomUUID());
        var recorder = mock(AssistantTraceRecorder.class);
        var unavailable = new IllegalStateException("Trace unavailable");
        doThrow(unavailable).when(recorder).append(eq(correlation), any(), eq("TOOL_EXECUTION"), eq("STARTED"), isNull(), isNull(), anyMap());
        try (var trace = AssistantTrace.open(recorder, correlation)) {
            assertThatThrownBy(() -> orchestrator.execute(actor, plan.id(), true, "Bearer current", 1, "digest"))
                    .isSameAs(unavailable);
        }
        verifyNoInteractions(executor);
        verify(store, never()).actionStarted(saved, scope, action);

        var outcomeRecorder = mock(AssistantTraceRecorder.class);
        doThrow(unavailable).when(outcomeRecorder).append(eq(correlation), any(), eq("TOOL_EXECUTION"), eq("SUCCEEDED"), isNull(), anyLong(), anyMap());
        String key = PlanStore.actionKey(scope, plan.id(), 1, action.id());
        var receipt = new ActionExecutionResponse(action.id(), "CREATE_DEVICE", "SUCCESS", Map.of("deviceId", UUID.randomUUID()), null);
        when(executor.execute(eq(action), eq("Bearer current"), eq(actor.userId()), anyMap(), eq(key))).thenReturn(receipt);
        try (var trace = AssistantTrace.open(outcomeRecorder, correlation)) {
            assertThat(orchestrator.execute(actor, plan.id(), true, "Bearer current", 1, "digest").status()).isEqualTo("SUCCESS");
        }
        verify(executor, times(1)).execute(eq(action), eq("Bearer current"), eq(actor.userId()), anyMap(), eq(key));
        verify(store).actionFinished(saved, scope, receipt);
        verify(store).finish(eq(saved), eq(scope), argThat(result -> result.status().equals("SUCCESS")));
    }

    @Test void lostResponseUsesTheOriginalReviewedPayloadAndKeyAndNeverReplaysLegacyChains() {
        var actor = new AuthenticatedUser(UUID.randomUUID(), "fixture@example.invalid", "USER");
        var scope = new AuthorizedPlanningContext.Scope(actor.userId(), UUID.randomUUID());
        var action = new AgentAction(UUID.randomUUID(), AgentTool.CREATE_DEVICE, "Create", "One reviewed device", true,
                Map.of("name", "Reviewed", "type", "FRIDGE"));
        var plan = new AgentPlan(UUID.randomUUID(), actor.userId(), "Создай «Reviewed»", "Review", "One device", List.of(), List.of(action), Instant.now());
        var saved = new PlanStore.Stored(plan, scope.accountId(), 1, "digest", Instant.now().plusSeconds(3600), "UNKNOWN", null, List.of());
        var contexts = mock(AuthorizedPlanningContext.class);
        when(contexts.scope(actor, "Bearer current")).thenReturn(scope);
        var store = mock(PlanStore.class);
        when(store.find(plan.id(), scope)).thenReturn(saved);
        when(store.claim(saved, scope, 1, "digest")).thenReturn(false);
        when(store.claimReconciliation(saved, scope)).thenReturn(true);
        var executor = mock(ToolExecutor.class);
        String key = PlanStore.actionKey(scope, plan.id(), 1, action.id());
        when(executor.execute(eq(action), eq("Bearer current"), eq(actor.userId()), anyMap(), eq(key)))
                .thenReturn(new ActionExecutionResponse(action.id(), "CREATE_DEVICE", "SUCCESS", Map.of("deviceId", "committed-device"), null));
        var reconciliation = new CreationReconciliation(executor);
        var orchestrator = new AiAgentOrchestrator(mock(CreationTaskPlanning.class), store, executor, mock(AuditLogger.class), contexts, reconciliation, mock(PlanClarifications.class));
        assertThat(orchestrator.execute(actor, plan.id(), true, "Bearer current", 1, "digest").status()).isEqualTo("SUCCESS");
        verify(executor).execute(eq(action), eq("Bearer current"), eq(actor.userId()), anyMap(), eq(key));
        verify(contexts).revalidate(List.of(), "Bearer current");
        verify(store).finish(eq(saved), eq(scope), argThat(result -> result.status().equals("SUCCESS")));
        assertThat(reconciliation.supports(new AgentAction(UUID.randomUUID(), AgentTool.CREATE_DEMO_DEVICE,
                "Demo", "Legacy chain", true, Map.of()))).isFalse();
    }
}
