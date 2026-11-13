package com.voltcanary.aiagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.aiagent.conversation.ConversationContracts;
import com.voltcanary.aiagent.conversation.ConversationStore;
import com.voltcanary.aiagent.orchestration.contract.SkillRegistry;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.springframework.transaction.support.TransactionTemplate;

/** Import immutable legacy request/question context once; no provider call or domain mutation. */
public final class LegacyAssistantContinuation {
    private final PlanStore reviews;
    private final ConversationStore conversations;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;

    public LegacyAssistantContinuation(PlanStore reviews, ConversationStore conversations, ObjectMapper mapper, TransactionTemplate transactions) {
        this.reviews = reviews;
        this.conversations = conversations;
        this.mapper = mapper;
        this.transactions = transactions;
    }

    public UUID prepare(AuthorizedPlanningContext.Scope owner, UUID reviewId) {
        // The calling application already revalidates the original review's resource access.
        var saved = reviews.find(reviewId, owner);
        UUID conversation = UUID.nameUUIDFromBytes(("spring-ai-import:" + owner + ":" + reviewId).getBytes(StandardCharsets.UTF_8));
        UUID requestId = UUID.nameUUIDFromBytes((conversation + ":original").getBytes(StandardCharsets.UTF_8));
        conversations.create(owner, conversation);
        var request = new ConversationContracts.Submit(requestId, 0, saved.plan().userMessage(), Map.of(), null,
                saved.plan().providerId(), saved.plan().modelConfigId(), null, false, null);
        String digest = SkillRegistry.digest(mapper.valueToTree(request).toString());
        transactions.executeWithoutResult(transaction -> {
            var imported = conversations.begin(owner, conversation, request, digest);
            if (imported.claimed()) conversations.finish(owner, conversation, requestId, reviewId, null, saved.plan().explanation(), "COMPLETED");
        });
        return conversation;
    }
}
