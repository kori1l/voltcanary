package com.voltcanary.aiagent.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "ai_agent_model_runs")
@Getter
@Setter
public class AiAgentModelRun {
    @Id
    private UUID id;
    private UUID accountId;
    private UUID userId;
    private UUID planId;
    private UUID providerId;
    private UUID modelConfigId;
    private String providerType;
    private String modelId;
    private String status;
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;
    private Integer latencyMs;
    private String errorCode;
    private String errorMessage;
    private Instant createdAt;

}
