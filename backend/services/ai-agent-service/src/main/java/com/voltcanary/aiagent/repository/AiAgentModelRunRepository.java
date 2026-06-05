package com.voltcanary.aiagent.repository;

import com.voltcanary.aiagent.domain.AiAgentModelRun;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;


public interface AiAgentModelRunRepository extends JpaRepository<AiAgentModelRun, UUID> {
}
