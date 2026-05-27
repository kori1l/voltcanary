package com.voltcanary.aiagent.provider;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiModelConfigRepository extends JpaRepository<AiModelConfig, UUID> {
    List<AiModelConfig> findByProviderIdOrderByCreatedAtAsc(UUID providerId);
    Optional<AiModelConfig> findByIdAndProviderId(UUID id, UUID providerId);
    Optional<AiModelConfig> findByProviderIdAndDefaultModelTrue(UUID providerId);
}
