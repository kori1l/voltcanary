package com.voltcanary.aiagent;

import com.voltcanary.aiagent.dto.*;
import com.voltcanary.aiagent.model.*;
import com.voltcanary.aiagent.service.*;
import com.voltcanary.aiagent.audit.AuditLogger;
import com.voltcanary.aiagent.security.AuthenticatedUser;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CreationReconciliationTest {
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
