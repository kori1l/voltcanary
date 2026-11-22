package com.voltcanary.aiagent.service;

import com.voltcanary.aiagent.domain.AiModelProviderStatus;
import com.voltcanary.aiagent.dto.ProviderStatusResponse;
import com.voltcanary.aiagent.repository.AiModelConfigRepository;
import com.voltcanary.aiagent.repository.AiModelProviderRepository;
import com.voltcanary.aiagent.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DefaultProviderStatus {

    private final AiModelProviderRepository providers;
    private final AiModelConfigRepository models;
    private final PrimaryAssistant primary;

    @Transactional(readOnly = true)
    public ProviderStatusResponse read(AuthenticatedUser user) {
        var provider = providers.findByAccountIdAndDefaultProviderTrue(user.accountId());
        if (provider.isEmpty()) {
            return new ProviderStatusResponse(
                "spring-ai",
                "Configured model required",
                false,
                "none"
            );
        }
        var model = models.findByProviderIdAndDefaultModelTrue(provider.get().getId());
        return new ProviderStatusResponse(
            provider.get().getProviderType().name(),
            model.map(item -> item.getModelId()).orElse("Configured model required"),
            primary.enabled() &&
                provider.get().getStatus() == AiModelProviderStatus.ACTIVE &&
                model.isPresent() &&
                model.get().isEnabled(),
            "none"
        );
    }
}
