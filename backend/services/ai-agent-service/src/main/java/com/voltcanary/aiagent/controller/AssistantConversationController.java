package com.voltcanary.aiagent.controller;

import com.voltcanary.aiagent.conversation.AssistantConversations;
import com.voltcanary.aiagent.conversation.ConversationContracts;
import com.voltcanary.aiagent.conversation.ConversationStore;
import com.voltcanary.aiagent.security.JwtIdentityService;
import com.voltcanary.aiagent.service.AuthorizedPlanningContext;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/ai-agent/conversations")
@RequiredArgsConstructor
public class AssistantConversationController {
    private final JwtIdentityService identity;
    private final ConversationStore store;
    private final AssistantConversations conversations;
    private final AuthorizedPlanningContext contexts;
    private final com.voltcanary.aiagent.service.PrimaryAssistant primary;

    @PostMapping
    public ConversationContracts.Conversation create(@RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody ConversationContracts.Create request) {
        return store.create(scope(authorization), request.conversationId());
    }

    @GetMapping
    public ConversationContracts.History list(@RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(value = "before", required = false) UUID before, @RequestParam(value = "limit", defaultValue = "30") int limit) {
        var user = scope(authorization);
        return new ConversationContracts.History(store.list(user, before, limit), store.selected(user));
    }

    @PatchMapping("/{id}")
    public ConversationContracts.Conversation select(@RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable("id") UUID id) {
        var user = scope(authorization);
        store.select(user, id);
        return store.get(user, id);
    }

    @GetMapping("/{id}")
    public ConversationContracts.Detail get(@RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable("id") UUID id) {
        var user = identity.parseBearerToken(authorization);
        if (primary.hasConversation(user, id, authorization)) return primary.detail(user, id, authorization);
        return conversations.detail(identity.parseBearerToken(authorization), id, authorization);
    }

    @GetMapping("/{id}/messages")
    public ConversationContracts.Page<ConversationContracts.Message> messages(
            @RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable("id") UUID id,
            @RequestParam(value = "after", defaultValue = "0") long after, @RequestParam(value = "limit", defaultValue = "100") int limit) {
        if (after < 0) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "Invalid cursor");
        var user = scope(authorization);
        store.reconcile(user, id);
        return store.messages(user, id, after, limit);
    }

    @PostMapping("/{id}/messages")
    public ConversationContracts.Detail submit(@RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable("id") UUID id, @Valid @RequestBody ConversationContracts.Submit request) {
        return primary.submit(identity.parseBearerToken(authorization), id, request, authorization);
    }

    private AuthorizedPlanningContext.Scope scope(String authorization) {
        return contexts.scope(identity.parseBearerToken(authorization), authorization);
    }
}
