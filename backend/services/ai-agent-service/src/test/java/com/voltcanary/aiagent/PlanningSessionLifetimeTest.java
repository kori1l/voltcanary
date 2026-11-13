package com.voltcanary.aiagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.aiagent.adapter.PrimaryOrchestrationJournal;
import com.voltcanary.aiagent.config.PrimaryAssistantProperties;
import com.voltcanary.aiagent.conversation.ConversationContracts;
import com.voltcanary.aiagent.llm.adapter.AiModelResolver;
import com.voltcanary.aiagent.orchestration.plan.IntentPlans;
import com.voltcanary.aiagent.security.AuthenticatedUser;
import com.voltcanary.aiagent.service.AuthorizedPlanningContext;
import com.voltcanary.aiagent.service.PrimaryAssistant;
import com.voltcanary.aiagent.service.PrimaryRuntime;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlanningSessionLifetimeTest {
    @Test void nearExpiryStopsBeforeModelResolutionOrTurnSubmission() {
        var user = new AuthenticatedUser(UUID.randomUUID(), "fixture@example.test", "USER", Instant.now().plusSeconds(20));
        var authority = mock(AuthorizedPlanningContext.class);
        var scope = new AuthorizedPlanningContext.Scope(user.userId(), UUID.randomUUID());
        when(authority.scope(user, "Bearer fixture")).thenReturn(scope);
        var journal = mock(PrimaryOrchestrationJournal.class);
        when(journal.turn(any(), any(), any())).thenReturn(Optional.empty());
        var models = mock(AiModelResolver.class);
        var runtime = mock(PrimaryRuntime.class);
        var properties = new PrimaryAssistantProperties();
        properties.setEnabled(true);
        var assistant = new PrimaryAssistant(new PrimaryAssistant.Ports(runtime, journal, null, null, null,
                models, null, authority, new ObjectMapper(), properties, null));
        var request = new ConversationContracts.Submit(UUID.randomUUID(), 0, "Prepare a new device", Map.of(), null, null, null, null, false, null);
        var failure = assertThrows(IntentPlans.Rejected.class,
                () -> assistant.submit(user, UUID.randomUUID(), request, "Bearer fixture"));
        assertEquals(401, failure.status());
        assertEquals("ASSISTANT_SESSION_REFRESH_REQUIRED", failure.getMessage());
        verifyNoInteractions(models, runtime);
    }
}
