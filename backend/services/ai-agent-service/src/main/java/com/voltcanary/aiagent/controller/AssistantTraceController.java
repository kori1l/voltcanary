package com.voltcanary.aiagent.controller;

import com.voltcanary.aiagent.conversation.ConversationContracts.Page;
import com.voltcanary.aiagent.port.AssistantTraceRecorder.Event;
import com.voltcanary.aiagent.port.AssistantTraces;
import com.voltcanary.aiagent.security.JwtIdentityService;
import com.voltcanary.aiagent.service.AuthorizedPlanningContext;
import java.util.ArrayList;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/ai-agent/conversations/{conversationId}/turns/{turnId}/trace")
@RequiredArgsConstructor
public class AssistantTraceController {
    private final JwtIdentityService identity;
    private final AuthorizedPlanningContext contexts;
    private final AssistantTraces traces;

    @GetMapping
    public Page<Event> read(@RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable("conversationId") UUID conversationId, @PathVariable("turnId") UUID turnId,
            @RequestParam(value = "after", defaultValue = "0") long after, @RequestParam(value = "limit", defaultValue = "100") int limit) {
        return traces.read(contexts.scope(identity.parseBearerToken(authorization), authorization), conversationId, turnId, after, limit);
    }

    @GetMapping("/export")
    public ResponseEntity<java.util.List<Event>> export(@RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable("conversationId") UUID conversationId, @PathVariable("turnId") UUID turnId) {
        var owner = contexts.scope(identity.parseBearerToken(authorization), authorization);
        var events = new ArrayList<Event>();
        long after = 0;
        Page<Event> page;
        do {
            page = traces.read(owner, conversationId, turnId, after, 100);
            events.addAll(page.items());
            if (page.nextCursor() != null) after = Long.parseLong(page.nextCursor());
        } while (page.nextCursor() != null);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=assistant-trace-" + turnId + ".json").body(events);
    }
}
