package com.voltcanary.aiagent.port;

import com.voltcanary.aiagent.conversation.ConversationContracts.Page;
import com.voltcanary.aiagent.service.AuthorizedPlanningContext.Scope;
import java.util.Optional;
import java.util.UUID;

public interface AssistantTraces extends AssistantTraceRecorder {
    Correlation input(Scope owner, UUID conversation, UUID request);
    Optional<Correlation> plan(Scope owner, UUID plan);
    Page<Event> read(Scope owner, UUID conversation, UUID turn, long after, int limit);
}
