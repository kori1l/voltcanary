package com.voltcanary.aiagent;

import com.voltcanary.aiagent.config.ConversationProperties;
import com.voltcanary.aiagent.conversation.ConversationStore;
import com.voltcanary.aiagent.model.AgentPlan;
import com.voltcanary.aiagent.service.AuthorizedPlanningContext;
import com.voltcanary.aiagent.service.LegacyAssistantContinuation;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LegacyAssistantContinuationTest {
    @Test
    void importsOriginalRequestAndExactQuestionOnceAndRejectsAnotherOwner() throws Exception {
        var f = new IntentScenarioSupport();
        var plan = new AgentPlan(UUID.randomUUID(), f.owner, "Создай датчик температуры", "Нужно имя", "Как назвать датчик?",
                List.of(), List.of(), f.now);
        f.reviews.save(plan, new AuthorizedPlanningContext.Prepared(f.session.owner(), Map.of(), List.of()));
        var history = new ConversationStore(f.jdbc, f.transactions, new ConversationProperties(), f.mapper);
        var migration = new LegacyAssistantContinuation(f.reviews, history, f.mapper, f.transactions);
        UUID conversation = migration.prepare(f.session.owner(), plan.id());
        assertEquals(conversation, migration.prepare(f.session.owner(), plan.id()));
        var messages = history.messages(f.session.owner(), conversation, 0, 10).items();
        assertEquals(2, messages.size());
        assertEquals("Создай датчик температуры", messages.getFirst().text());
        assertEquals("Как назвать датчик?", messages.getLast().text());
        assertEquals(plan.id(), messages.getLast().planId());
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> migration.prepare(
                new AuthorizedPlanningContext.Scope(UUID.randomUUID(), UUID.randomUUID()), plan.id()));
    }
}
