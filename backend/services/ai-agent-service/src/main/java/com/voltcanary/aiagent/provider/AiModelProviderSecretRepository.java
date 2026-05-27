package com.voltcanary.aiagent.provider;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiModelProviderSecretRepository extends JpaRepository<AiModelProviderSecret, UUID> {
    Optional<AiModelProviderSecret> findByProviderIdAndSecretType(UUID providerId, AiModelSecretType secretType);
}
