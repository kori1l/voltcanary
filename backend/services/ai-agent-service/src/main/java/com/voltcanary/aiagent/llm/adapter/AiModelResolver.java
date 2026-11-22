package com.voltcanary.aiagent.llm.adapter;

import com.voltcanary.aiagent.domain.AiModelConfig;
import com.voltcanary.aiagent.domain.AiModelProvider;
import com.voltcanary.aiagent.domain.AiModelProviderStatus;
import com.voltcanary.aiagent.domain.AiModelSecretType;
import com.voltcanary.aiagent.repository.AiModelConfigRepository;
import com.voltcanary.aiagent.repository.AiModelProviderRepository;
import com.voltcanary.aiagent.repository.AiModelProviderSecretRepository;
import com.voltcanary.aiagent.security.AuthenticatedUser;
import com.voltcanary.aiagent.service.AiSecretEncryptionService;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AiModelResolver {

    private final AiModelProviderRepository providerRepository;
    private final AiModelConfigRepository modelRepository;
    private final AiModelProviderSecretRepository secretRepository;
    private final AiSecretEncryptionService encryptionService;

    public Optional<AiResolvedModel> resolve(
        AuthenticatedUser user,
        UUID providerId,
        UUID modelConfigId
    ) {
        Optional<AiModelProvider> providerOptional =
            providerId == null
                ? providerRepository.findByAccountIdAndDefaultProviderTrue(user.accountId())
                : providerRepository.findByIdAndAccountId(providerId, user.accountId());
        if (
            providerOptional.isEmpty() ||
            providerOptional.get().getStatus() != AiModelProviderStatus.ACTIVE
        ) {
            if (
                providerId != null || modelConfigId != null
            ) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND,
                "Selected provider unavailable"
            );
            return Optional.empty();
        }
        AiModelProvider provider = providerOptional.get();
        Optional<AiModelConfig> modelOptional =
            modelConfigId == null
                ? modelRepository.findByProviderIdAndDefaultModelTrue(provider.getId())
                : modelRepository.findByIdAndProviderId(modelConfigId, provider.getId());
        if (modelOptional.isEmpty() || !modelOptional.get().isEnabled()) {
            if (
                modelConfigId != null
            ) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND,
                "Selected model unavailable"
            );
            return Optional.empty();
        }
        String apiKey = secretRepository
            .findByProviderIdAndSecretType(provider.getId(), AiModelSecretType.API_KEY)
            .map(secret ->
                encryptionService.decrypt(
                    secret.getEncryptedValue(),
                    secret.getEncryptionKeyVersion()
                )
            )
            .orElse("");
        if (!provider.getAuthType().name().equals("NONE") && apiKey.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new AiResolvedModel(provider, modelOptional.get(), apiKey));
    }
}
