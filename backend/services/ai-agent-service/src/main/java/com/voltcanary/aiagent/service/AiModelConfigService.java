package com.voltcanary.aiagent.service;

import com.voltcanary.aiagent.domain.AiModelConfig;
import com.voltcanary.aiagent.domain.AiModelProvider;
import com.voltcanary.aiagent.dto.CreateModelConfigRequest;
import com.voltcanary.aiagent.dto.ModelConfigResponse;
import com.voltcanary.aiagent.dto.UpdateModelConfigRequest;
import com.voltcanary.aiagent.mapper.AiProviderMapper;
import com.voltcanary.aiagent.repository.AiModelConfigRepository;
import com.voltcanary.aiagent.repository.AiModelProviderRepository;
import com.voltcanary.aiagent.security.AuthenticatedUser;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;


@Service
public class AiModelConfigService {
    private final AiModelProviderRepository providerRepository;
    private final AiModelConfigRepository modelRepository;

    public AiModelConfigService(AiModelProviderRepository providerRepository, AiModelConfigRepository modelRepository) {
        this.providerRepository = providerRepository;
        this.modelRepository = modelRepository;
    }

    public List<ModelConfigResponse> list(AuthenticatedUser user, UUID providerId) {
        requireProvider(user, providerId);
        return modelRepository.findByProviderIdOrderByCreatedAtAsc(providerId).stream()
                .map(AiProviderMapper::model)
                .toList();
    }

    @Transactional
    public ModelConfigResponse create(AuthenticatedUser user, UUID providerId, CreateModelConfigRequest request) {
        requireProvider(user, providerId);
        Instant now = Instant.now();
        AiModelConfig model = new AiModelConfig();
        model.setId(UUID.randomUUID());
        model.setProviderId(providerId);
        model.setDisplayName(request.displayName());
        model.setModelId(request.modelId());
        model.setEnabled(request.enabled() == null || request.enabled());
        model.setDefaultModel(Boolean.TRUE.equals(request.makeDefault()) || modelRepository.findByProviderIdOrderByCreatedAtAsc(providerId).isEmpty());
        model.setTemperature(request.temperature());
        model.setTopP(request.topP());
        model.setMaxOutputTokens(request.maxOutputTokens());
        model.setTimeoutMs(request.timeoutMs());
        model.setSupportsTools(Boolean.TRUE.equals(request.supportsTools()));
        model.setSupportsJson(request.supportsJson() == null || request.supportsJson());
        model.setResponseFormat(request.responseFormat());
        model.setCreatedAt(now);
        model.setUpdatedAt(now);
        if (model.isDefaultModel()) {
            unsetDefaultModels(providerId);
        }
        return AiProviderMapper.model(modelRepository.save(model));
    }

    @Transactional
    public ModelConfigResponse update(AuthenticatedUser user, UUID providerId, UUID modelConfigId, UpdateModelConfigRequest request) {
        requireProvider(user, providerId);
        AiModelConfig model = modelRepository.findByIdAndProviderId(modelConfigId, providerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Model config not found"));
        if (request.displayName() != null) model.setDisplayName(request.displayName());
        if (request.modelId() != null) model.setModelId(request.modelId());
        if (request.enabled() != null) model.setEnabled(request.enabled());
        if (request.temperature() != null) model.setTemperature(request.temperature());
        if (request.topP() != null) model.setTopP(request.topP());
        if (request.maxOutputTokens() != null) model.setMaxOutputTokens(request.maxOutputTokens());
        if (request.timeoutMs() != null) model.setTimeoutMs(request.timeoutMs());
        if (request.supportsTools() != null) model.setSupportsTools(request.supportsTools());
        if (request.supportsJson() != null) model.setSupportsJson(request.supportsJson());
        if (request.responseFormat() != null) model.setResponseFormat(request.responseFormat());
        model.setUpdatedAt(Instant.now());
        return AiProviderMapper.model(modelRepository.save(model));
    }

    @Transactional
    public void delete(AuthenticatedUser user, UUID providerId, UUID modelConfigId) {
        requireProvider(user, providerId);
        AiModelConfig model = modelRepository.findByIdAndProviderId(modelConfigId, providerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Model config not found"));
        modelRepository.delete(model);
    }

    @Transactional
    public ModelConfigResponse setDefault(AuthenticatedUser user, UUID providerId, UUID modelConfigId) {
        requireProvider(user, providerId);
        AiModelConfig model = modelRepository.findByIdAndProviderId(modelConfigId, providerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Model config not found"));
        if (!model.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Disabled model cannot be default");
        }
        unsetDefaultModels(providerId);
        model.setDefaultModel(true);
        model.setUpdatedAt(Instant.now());
        return AiProviderMapper.model(modelRepository.save(model));
    }

    AiModelProvider requireProvider(AuthenticatedUser user, UUID providerId) {
        return providerRepository.findByIdAndAccountId(providerId, user.accountId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Model provider not found"));
    }

    private void unsetDefaultModels(UUID providerId) {
        for (AiModelConfig existing : modelRepository.findByProviderIdOrderByCreatedAtAsc(providerId)) {
            if (existing.isDefaultModel()) {
                existing.setDefaultModel(false);
                existing.setUpdatedAt(Instant.now());
                modelRepository.save(existing);
            }
        }
    }
}
