package com.voltcanary.aiagent.provider;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiAgentModelRunRepository extends JpaRepository<AiAgentModelRun, UUID> {
}
