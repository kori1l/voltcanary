package com.voltcanary.aiagent.repository;

import com.voltcanary.aiagent.domain.AiModelProvider;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;


public interface AiModelProviderRepository extends JpaRepository<AiModelProvider, UUID> {
    List<AiModelProvider> findByAccountIdOrderByCreatedAtDesc(UUID accountId);
    Optional<AiModelProvider> findByIdAndAccountId(UUID id, UUID accountId);
    Optional<AiModelProvider> findByAccountIdAndDefaultProviderTrue(UUID accountId);
    List<AiModelProvider> findByAccountIdAndDefaultProviderTrueOrderByCreatedAtDesc(UUID accountId);
}
