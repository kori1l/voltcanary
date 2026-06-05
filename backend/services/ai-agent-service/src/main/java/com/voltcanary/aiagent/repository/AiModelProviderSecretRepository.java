package com.voltcanary.aiagent.repository;

import com.voltcanary.aiagent.domain.AiModelProviderSecret;
import com.voltcanary.aiagent.domain.AiModelSecretType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;


public interface AiModelProviderSecretRepository extends JpaRepository<AiModelProviderSecret, UUID> {
    Optional<AiModelProviderSecret> findByProviderIdAndSecretType(UUID providerId, AiModelSecretType secretType);
}
