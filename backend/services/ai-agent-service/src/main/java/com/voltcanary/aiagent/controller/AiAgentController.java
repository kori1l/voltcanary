package com.voltcanary.aiagent.controller;

import com.voltcanary.aiagent.config.AiAgentProperties;
import com.voltcanary.aiagent.dto.ExecutePlanRequest;
import com.voltcanary.aiagent.dto.ExecutePlanResponse;
import com.voltcanary.aiagent.dto.PlanRequest;
import com.voltcanary.aiagent.dto.PlanResponse;
import com.voltcanary.aiagent.dto.ProviderStatusResponse;
import com.voltcanary.aiagent.model.AgentTool;
import com.voltcanary.aiagent.security.AuthenticatedUser;
import com.voltcanary.aiagent.security.JwtIdentityService;
import com.voltcanary.aiagent.service.AiAgentOrchestrator;
import jakarta.validation.Valid;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/ai-agent")
public class AiAgentController {

    private final JwtIdentityService jwtIdentityService;
    private final AiAgentOrchestrator orchestrator;
    private final AiAgentProperties aiAgentProperties;

    public AiAgentController(
            JwtIdentityService jwtIdentityService,
            AiAgentOrchestrator orchestrator,
            AiAgentProperties aiAgentProperties) {
        this.jwtIdentityService = jwtIdentityService;
        this.orchestrator = orchestrator;
        this.aiAgentProperties = aiAgentProperties;
    }

    @PostMapping("/plans")
    public PlanResponse plan(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody PlanRequest request) {
        AuthenticatedUser user = jwtIdentityService.parseBearerToken(authorization);
        return orchestrator.createPlan(user, request.message(), request.context());
    }

    @PostMapping("/plans/{planId}/execute")
    public ExecutePlanResponse execute(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable("planId") UUID planId,
            @Valid @RequestBody ExecutePlanRequest request) {
        AuthenticatedUser user = jwtIdentityService.parseBearerToken(authorization);
        return orchestrator.execute(user, planId, request.confirmed(), authorization);
    }

    @GetMapping("/tools")
    public List<String> tools() {
        return Arrays.stream(AgentTool.values()).map(Enum::name).toList();
    }

    @GetMapping("/provider")
    public ProviderStatusResponse provider() {
        String provider = aiAgentProperties.getProvider();
        boolean openAiEnabled = "openai".equalsIgnoreCase(provider)
                && !aiAgentProperties.getOpenai().getApiKey().isBlank();
        String activeProvider = openAiEnabled ? "openai" : "rules";
        String model = openAiEnabled ? aiAgentProperties.getOpenai().getModel() : "rule-based planner";
        return new ProviderStatusResponse(activeProvider, model, openAiEnabled, "rules");
    }
}
