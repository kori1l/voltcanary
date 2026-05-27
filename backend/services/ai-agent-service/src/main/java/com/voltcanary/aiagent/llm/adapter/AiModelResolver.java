package com.voltcanary.aiagent.llm.adapter;

import com.voltcanary.aiagent.provider.AiModelConfig;
import com.voltcanary.aiagent.provider.AiModelConfigRepository;
import com.voltcanary.aiagent.provider.AiModelProvider;
import com.voltcanary.aiagent.provider.AiModelProviderRepository;
import com.voltcanary.aiagent.provider.AiModelProviderSecretRepository;
import com.voltcanary.aiagent.provider.AiModelProviderStatus;
import com.voltcanary.aiagent.provider.AiModelSecretType;
import com.voltcanary.aiagent.provider.AiSecretEncryptionService;
import com.voltcanary.aiagent.security.AuthenticatedUser;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class AiModelResolver {
    private final AiModelProviderRepository providerRepository;
    private final AiModelConfigRepository modelRepository;
    private final AiModelProviderSecretRepository secretRepository;
    private final AiSecretEncryptionService encryptionService;

    public AiModelResolver(
            AiModelProviderRepository providerRepository,
            AiModelConfigRepository modelRepository,
            AiModelProviderSecretRepository secretRepository,
            AiSecretEncryptionService encryptionService) {
        this.providerRepository = providerRepository;
        this.modelRepository = modelRepository;
        this.secretRepository = secretRepository;
        this.encryptionService = encryptionService;
    }

    public Optional<AiResolvedModel> resolve(AuthenticatedUser user, UUID providerId, UUID modelConfigId) {
        Optional<AiModelProvider> providerOptional = providerId == null
                ? providerRepository.findByAccountIdAndDefaultProviderTrue(user.accountId())
                : providerRepository.findByIdAndAccountId(providerId, user.accountId());
        if (providerOptional.isEmpty() || providerOptional.get().getStatus() != AiModelProviderStatus.ACTIVE) {
            return Optional.empty();
        }
        AiModelProvider provider = providerOptional.get();
        Optional<AiModelConfig> modelOptional = modelConfigId == null
                ? modelRepository.findByProviderIdAndDefaultModelTrue(provider.getId())
                : modelRepository.findByIdAndProviderId(modelConfigId, provider.getId());
        if (modelOptional.isEmpty() || !modelOptional.get().isEnabled()) {
            return Optional.empty();
        }
        String apiKey = secretRepository.findByProviderIdAndSecretType(provider.getId(), AiModelSecretType.API_KEY)
                .map(secret -> encryptionService.decrypt(secret.getEncryptedValue()))
                .orElse("");
        if (!provider.getAuthType().name().equals("NONE") && apiKey.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new AiResolvedModel(provider, modelOptional.get(), apiKey));
    }
}
