package com.voltcanary.aiagent.controller;

import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol;
import com.voltcanary.aiagent.security.JwtIdentityService;
import com.voltcanary.aiagent.service.PrimaryAssistant;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class PrimaryAssistantControlController {
    private final PrimaryAssistant primary;
    private final JwtIdentityService identity;

    @GetMapping("/ai-agent/orchestrations/runtime")
    public Map<String, Object> mode(@RequestHeader("Authorization") String authorization) {
        primary.session(identity.parseBearerToken(authorization), authorization);
        return Map.of("generation", primary.enabled() ? "assistant-v2" : "disabled", "engine", "spring-ai-2", "hiddenFallback", false, "routing", "PINNED", "qualified", false);
    }
    @PostMapping("/ai-agent/orchestrations/{id}/control")
    public Object control(@RequestHeader("Authorization") String authorization, @PathVariable("id") UUID id, @RequestBody AssistantProtocol.ControlRequest request) {
        return primary.control(identity.parseBearerToken(authorization), id, request, authorization).wire();
    }
}
