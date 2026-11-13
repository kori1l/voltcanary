package com.voltcanary.aiagent;

import com.voltcanary.aiagent.conversation.ConversationRedaction;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol;
import com.voltcanary.aiagent.orchestration.plan.OrchestrationPorts;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PrimaryRedactionProvenanceTest {
    @Test void primaryJournalRetainsMaskProvenanceAfterApplicationRedaction() throws Exception {
        var fixture = new IntentScenarioSupport();
        var models = mock(OrchestrationPorts.Models.class);
        when(models.plan(any(), any(), any())).thenReturn(new AssistantProtocol.PlannerResponse(
                "assistant-v2", "planner.response", "ASK", "Select a resource", null, List.of(),
                new AssistantProtocol.Question("Which resource?", List.of("target"), List.of()), List.of(), List.of(), List.of()));
        fixture.send(fixture.orchestration(models), null, ConversationRedaction.text("Inspect resource; password=synthetic-only"));
        assertEquals(Boolean.TRUE, fixture.jdbc.queryForObject(
                "select redacted from ai_conversation_messages where conversation_id=? and kind='INPUT'",
                Boolean.class, fixture.conversation));
    }
}
