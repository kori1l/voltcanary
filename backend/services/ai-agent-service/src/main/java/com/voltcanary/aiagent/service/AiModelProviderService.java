package com.voltcanary.aiagent.service;

import com.voltcanary.aiagent.domain.AiModelAuthType;
import com.voltcanary.aiagent.domain.AiModelConfig;
import com.voltcanary.aiagent.domain.AiModelProvider;
import com.voltcanary.aiagent.domain.AiModelProviderSecret;
import com.voltcanary.aiagent.domain.AiModelProviderStatus;
import com.voltcanary.aiagent.domain.AiModelProviderType;
import com.voltcanary.aiagent.domain.AiModelSecretType;
import com.voltcanary.aiagent.domain.EncryptedSecret;
import com.voltcanary.aiagent.dto.CreateModelConfigRequest;
import com.voltcanary.aiagent.dto.CreateModelProviderRequest;
import com.voltcanary.aiagent.dto.ModelConfigResponse;
import com.voltcanary.aiagent.dto.ModelProviderResponse;
import com.voltcanary.aiagent.dto.RotateModelProviderSecretRequest;
import com.voltcanary.aiagent.dto.TestModelProviderResponse;
import com.voltcanary.aiagent.dto.UpdateModelProviderRequest;
import com.voltcanary.aiagent.mapper.AiProviderMapper;
import com.voltcanary.aiagent.repository.AiModelConfigRepository;
import com.voltcanary.aiagent.repository.AiModelProviderRepository;
import com.voltcanary.aiagent.repository.AiModelProviderSecretRepository;
import com.voltcanary.aiagent.security.AuthenticatedUser;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;


@Service
public class AiModelProviderService {
    private final AiModelProviderRepository providerRepository;
    private final AiModelProviderSecretRepository secretRepository;
    private final AiModelConfigRepository modelRepository;
    private final AiModelSelectionService modelSelectionService;
    private final AiModelProviderCapabilityRegistry capabilityRegistry;
    private final AiSecretEncryptionService encryptionService;

    public AiModelProviderService(
            AiModelProviderRepository providerRepository,
            AiModelProviderSecretRepository secretRepository,
            AiModelConfigRepository modelRepository,
            AiModelSelectionService modelSelectionService,
            AiModelProviderCapabilityRegistry capabilityRegistry,
            AiSecretEncryptionService encryptionService) {
        this.providerRepository = providerRepository;
        this.secretRepository = secretRepository;
        this.modelRepository = modelRepository;
        this.modelSelectionService = modelSelectionService;
        this.capabilityRegistry = capabilityRegistry;
        this.encryptionService = encryptionService;
    }

    public List<ModelProviderResponse> list(AuthenticatedUser user) {
        return providerRepository.findByAccountIdOrderByCreatedAtDesc(user.accountId()).stream()
                .map(this::toResponse)
                .toList();
    }

    public ModelProviderResponse get(AuthenticatedUser user, UUID providerId) {
        return toResponse(requireProvider(user, providerId));
    }

    @Transactional
    public ModelProviderResponse create(AuthenticatedUser user, CreateModelProviderRequest request) {
        AiModelProviderType type = parseProviderType(request.providerType());
        if (!type.equals(AiModelProviderType.OLLAMA_LOCAL) && (request.apiKey() == null || request.apiKey().isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "API key is required for this provider");
        }
        var capability = capabilityRegistry.capability(type);
        Instant now = Instant.now();
        boolean firstProvider = providerRepository.findByAccountIdOrderByCreatedAtDesc(user.accountId()).isEmpty();
        AiModelProvider provider = new AiModelProvider();
        provider.setId(UUID.randomUUID());
        provider.setAccountId(user.accountId());
        provider.setCreatedByUserId(user.userId());
        provider.setName(request.name());
        provider.setProviderType(type);
        provider.setStatus(AiModelProviderStatus.ACTIVE);
        provider.setDefaultProvider(firstProvider || Boolean.TRUE.equals(request.makeDefault()));
        provider.setBaseUrl(normalizeBaseUrl(request.baseUrl(), capability.defaultBaseUrl()));
        provider.setAuthType(type.equals(AiModelProviderType.OLLAMA_LOCAL) ? AiModelAuthType.NONE : AiModelAuthType.API_KEY);
        provider.setCreatedAt(now);
        provider.setUpdatedAt(now);
        if (provider.isDefaultProvider()) {
            unsetDefaultProviders(user.accountId());
        }
        AiModelProvider saved = providerRepository.save(provider);
        if (request.apiKey() != null && !request.apiKey().isBlank()) {
            saveSecret(saved, request.apiKey(), now, false);
        }
        modelSelectionService.create(user, saved.getId(), new CreateModelConfigRequest(
                request.modelDisplayName() == null || request.modelDisplayName().isBlank() ? request.modelId() : request.modelDisplayName(),
                request.modelId(),
                true,
                true,
                request.temperature(),
                request.topP(),
                request.maxOutputTokens(),
                request.timeoutMs(),
                request.supportsTools(),
                request.supportsJson(),
                request.supportsJson() == null || request.supportsJson() ? "json_object" : null));
        return toResponse(saved);
    }

    @Transactional
    public ModelProviderResponse update(AuthenticatedUser user, UUID providerId, UpdateModelProviderRequest request) {
        AiModelProvider provider = requireProvider(user, providerId);
        if (request.name() != null && !request.name().isBlank()) provider.setName(request.name());
        if (request.baseUrl() != null) provider.setBaseUrl(normalizeBaseUrl(request.baseUrl(), provider.getBaseUrl()));
        if (request.status() != null && !request.status().isBlank()) provider.setStatus(AiModelProviderStatus.valueOf(request.status()));
        provider.setUpdatedAt(Instant.now());
        return toResponse(providerRepository.save(provider));
    }

    @Transactional
    public void delete(AuthenticatedUser user, UUID providerId) {
        providerRepository.delete(requireProvider(user, providerId));
    }

    @Transactional
    public ModelProviderResponse setDefault(AuthenticatedUser user, UUID providerId) {
        AiModelProvider provider = requireProvider(user, providerId);
        unsetDefaultProviders(user.accountId());
        provider.setDefaultProvider(true);
        provider.setUpdatedAt(Instant.now());
        return toResponse(providerRepository.save(provider));
    }

    @Transactional
    public ModelProviderResponse rotateSecret(AuthenticatedUser user, UUID providerId, RotateModelProviderSecretRequest request) {
        AiModelProvider provider = requireProvider(user, providerId);
        saveSecret(provider, request.apiKey(), Instant.now(), true);
        return toResponse(providerRepository.findById(providerId).orElse(provider));
    }

    @Transactional
    public TestModelProviderResponse test(AuthenticatedUser user, UUID providerId) {
        AiModelProvider provider = requireProvider(user, providerId);
        AiModelConfig model = modelRepository.findByProviderIdAndDefaultModelTrue(providerId)
                .orElseGet(() -> modelRepository.findByProviderIdOrderByCreatedAtAsc(providerId).stream().findFirst()
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Provider has no models")));
        Instant now = Instant.now();
        TestModelProviderResponse response = new TestModelProviderResponse(
                "READY",
                "Provider config is present. Live provider call will run during agent planning.",
                provider.getProviderType().name(),
                model.getModelId(),
                0,
                now);
        provider.setLastTestStatus(response.status());
        provider.setLastTestMessage(response.message());
        provider.setLastTestAt(now);
        provider.setUpdatedAt(now);
        providerRepository.save(provider);
        return response;
    }

    public AiModelProvider requireProvider(AuthenticatedUser user, UUID providerId) {
        return providerRepository.findByIdAndAccountId(providerId, user.accountId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Model provider not found"));
    }

    private ModelProviderResponse toResponse(AiModelProvider provider) {
        List<ModelConfigResponse> models = modelRepository.findByProviderIdOrderByCreatedAtAsc(provider.getId()).stream()
                .map(AiProviderMapper::model)
                .toList();
        return AiProviderMapper.provider(provider, models);
    }

    private void saveSecret(AiModelProvider provider, String apiKey, Instant now, boolean rotated) {
        EncryptedSecret encrypted = encryptionService.encrypt(apiKey);
        AiModelProviderSecret secret = secretRepository.findByProviderIdAndSecretType(provider.getId(), AiModelSecretType.API_KEY)
                .orElseGet(() -> {
                    AiModelProviderSecret next = new AiModelProviderSecret();
                    next.setId(UUID.randomUUID());
                    next.setProviderId(provider.getId());
                    next.setSecretType(AiModelSecretType.API_KEY);
                    next.setCreatedAt(now);
                    return next;
                });
        secret.setEncryptedValue(encrypted.encryptedValue());
        secret.setEncryptionKeyVersion(encrypted.keyVersion());
        if (rotated) {
            secret.setRotatedAt(now);
        }
        secretRepository.save(secret);
        provider.setMaskedSecretHint(encryptionService.mask(apiKey));
        provider.setUpdatedAt(now);
        providerRepository.save(provider);
    }

    private void unsetDefaultProviders(UUID accountId) {
        for (AiModelProvider existing : providerRepository.findByAccountIdAndDefaultProviderTrueOrderByCreatedAtDesc(accountId)) {
            existing.setDefaultProvider(false);
            existing.setUpdatedAt(Instant.now());
            providerRepository.save(existing);
        }
    }

    private AiModelProviderType parseProviderType(String value) {
        try {
            return AiModelProviderType.valueOf(value.trim().toUpperCase());
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported provider type");
        }
    }

    private String normalizeBaseUrl(String value, String fallback) {
        String result = value == null || value.isBlank() ? fallback : value.trim();
        if (result != null && result.endsWith("/")) {
            return result.substring(0, result.length() - 1);
        }
        return result;
    }
}
