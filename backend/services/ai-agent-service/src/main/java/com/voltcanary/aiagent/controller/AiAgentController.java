package com.voltcanary.aiagent.controller;

import com.voltcanary.aiagent.config.AiAgentProperties;
import com.voltcanary.aiagent.dto.CreateModelConfigRequest;
import com.voltcanary.aiagent.dto.CreateModelProviderRequest;
import com.voltcanary.aiagent.dto.ExecutePlanRequest;
import com.voltcanary.aiagent.dto.ExecutePlanResponse;
import com.voltcanary.aiagent.dto.ModelConfigResponse;
import com.voltcanary.aiagent.dto.ModelProviderCapabilityResponse;
import com.voltcanary.aiagent.dto.ModelProviderResponse;
import com.voltcanary.aiagent.dto.PlanRequest;
import com.voltcanary.aiagent.dto.PlanResponse;
import com.voltcanary.aiagent.dto.ProviderStatusResponse;
import com.voltcanary.aiagent.dto.RotateModelProviderSecretRequest;
import com.voltcanary.aiagent.dto.TestModelProviderResponse;
import com.voltcanary.aiagent.dto.UpdateModelConfigRequest;
import com.voltcanary.aiagent.dto.UpdateModelProviderRequest;
import com.voltcanary.aiagent.model.AgentTool;
import com.voltcanary.aiagent.repository.AiModelConfigRepository;
import com.voltcanary.aiagent.repository.AiModelProviderRepository;
import com.voltcanary.aiagent.security.AuthenticatedUser;
import com.voltcanary.aiagent.security.JwtIdentityService;
import com.voltcanary.aiagent.service.AiAgentOrchestrator;
import com.voltcanary.aiagent.service.AiModelConfigService;
import com.voltcanary.aiagent.service.AiModelProviderCapabilityRegistry;
import com.voltcanary.aiagent.service.AiModelProviderService;
import jakarta.validation.Valid;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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
    private final AiModelProviderCapabilityRegistry capabilityRegistry;
    private final AiModelProviderService providerService;
    private final AiModelConfigService modelConfigService;
    private final AiModelProviderRepository providerRepository;
    private final AiModelConfigRepository modelConfigRepository;

    public AiAgentController(
            JwtIdentityService jwtIdentityService,
            AiAgentOrchestrator orchestrator,
            AiAgentProperties aiAgentProperties,
            AiModelProviderCapabilityRegistry capabilityRegistry,
            AiModelProviderService providerService,
            AiModelConfigService modelConfigService,
            AiModelProviderRepository providerRepository,
            AiModelConfigRepository modelConfigRepository) {
        this.jwtIdentityService = jwtIdentityService;
        this.orchestrator = orchestrator;
        this.aiAgentProperties = aiAgentProperties;
        this.capabilityRegistry = capabilityRegistry;
        this.providerService = providerService;
        this.modelConfigService = modelConfigService;
        this.providerRepository = providerRepository;
        this.modelConfigRepository = modelConfigRepository;
    }

    @PostMapping("/plans")
    public PlanResponse plan(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody PlanRequest request) {
        AuthenticatedUser user = jwtIdentityService.parseBearerToken(authorization);
        return orchestrator.createPlan(user, request.message(), request.context(), request.providerId(), request.modelConfigId());
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
    public ProviderStatusResponse provider(@RequestHeader(value = "Authorization", required = false) String authorization) {
        try {
            AuthenticatedUser user = jwtIdentityService.parseBearerToken(authorization);
            var provider = providerRepository.findByAccountIdAndDefaultProviderTrue(user.accountId());
            if (provider.isPresent()) {
                var model = modelConfigRepository.findByProviderIdAndDefaultModelTrue(provider.get().getId());
                return new ProviderStatusResponse(provider.get().getProviderType().name(), model.map(item -> item.getModelId()).orElse("No default model"), true, "rules");
            }
        } catch (Exception ignored) {
            // Fall back to env provider below for backwards compatibility.
        }
        String provider = aiAgentProperties.getProvider();
        boolean openAiEnabled = "openai".equalsIgnoreCase(provider) && !aiAgentProperties.getOpenai().getApiKey().isBlank();
        return new ProviderStatusResponse(openAiEnabled ? "openai" : "rules", openAiEnabled ? aiAgentProperties.getOpenai().getModel() : "rule-based planner", openAiEnabled, "rules");
    }

    @GetMapping("/model-provider-capabilities")
    public List<ModelProviderCapabilityResponse> capabilities() {
        return capabilityRegistry.capabilities();
    }

    @GetMapping("/model-providers")
    public List<ModelProviderResponse> modelProviders(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return providerService.list(jwtIdentityService.parseBearerToken(authorization));
    }

    @PostMapping("/model-providers")
    public ModelProviderResponse createModelProvider(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody CreateModelProviderRequest request) {
        return providerService.create(jwtIdentityService.parseBearerToken(authorization), request);
    }

    @GetMapping("/model-providers/{providerId}")
    public ModelProviderResponse getModelProvider(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable("providerId") UUID providerId) {
        return providerService.get(jwtIdentityService.parseBearerToken(authorization), providerId);
    }

    @PutMapping("/model-providers/{providerId}")
    public ModelProviderResponse updateModelProvider(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable("providerId") UUID providerId,
            @Valid @RequestBody UpdateModelProviderRequest request) {
        return providerService.update(jwtIdentityService.parseBearerToken(authorization), providerId, request);
    }

    @DeleteMapping("/model-providers/{providerId}")
    public void deleteModelProvider(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable("providerId") UUID providerId) {
        providerService.delete(jwtIdentityService.parseBearerToken(authorization), providerId);
    }

    @PostMapping("/model-providers/{providerId}/set-default")
    public ModelProviderResponse setDefaultModelProvider(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable("providerId") UUID providerId) {
        return providerService.setDefault(jwtIdentityService.parseBearerToken(authorization), providerId);
    }

    @PostMapping("/model-providers/{providerId}/test")
    public TestModelProviderResponse testModelProvider(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable("providerId") UUID providerId) {
        return providerService.test(jwtIdentityService.parseBearerToken(authorization), providerId);
    }

    @PostMapping("/model-providers/{providerId}/rotate-secret")
    public ModelProviderResponse rotateModelProviderSecret(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable("providerId") UUID providerId,
            @Valid @RequestBody RotateModelProviderSecretRequest request) {
        return providerService.rotateSecret(jwtIdentityService.parseBearerToken(authorization), providerId, request);
    }

    @GetMapping("/model-providers/{providerId}/models")
    public List<ModelConfigResponse> modelConfigs(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable("providerId") UUID providerId) {
        return modelConfigService.list(jwtIdentityService.parseBearerToken(authorization), providerId);
    }

    @PostMapping("/model-providers/{providerId}/models")
    public ModelConfigResponse createModelConfig(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable("providerId") UUID providerId,
            @Valid @RequestBody CreateModelConfigRequest request) {
        return modelConfigService.create(jwtIdentityService.parseBearerToken(authorization), providerId, request);
    }

    @PutMapping("/model-providers/{providerId}/models/{modelConfigId}")
    public ModelConfigResponse updateModelConfig(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable("providerId") UUID providerId,
            @PathVariable("modelConfigId") UUID modelConfigId,
            @Valid @RequestBody UpdateModelConfigRequest request) {
        return modelConfigService.update(jwtIdentityService.parseBearerToken(authorization), providerId, modelConfigId, request);
    }

    @DeleteMapping("/model-providers/{providerId}/models/{modelConfigId}")
    public void deleteModelConfig(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable("providerId") UUID providerId,
            @PathVariable("modelConfigId") UUID modelConfigId) {
        modelConfigService.delete(jwtIdentityService.parseBearerToken(authorization), providerId, modelConfigId);
    }

    @PostMapping("/model-providers/{providerId}/models/{modelConfigId}/set-default")
    public ModelConfigResponse setDefaultModelConfig(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable("providerId") UUID providerId,
            @PathVariable("modelConfigId") UUID modelConfigId) {
        return modelConfigService.setDefault(jwtIdentityService.parseBearerToken(authorization), providerId, modelConfigId);
    }
}
