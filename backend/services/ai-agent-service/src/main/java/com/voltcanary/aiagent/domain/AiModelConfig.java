package com.voltcanary.aiagent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "ai_model_configs")
@Getter
@Setter
public class AiModelConfig {
    @Id
    private UUID id;
    private UUID providerId;
    private String displayName;
    private String modelId;
    private boolean enabled;
    @Column(name = "is_default")
    private boolean defaultModel;
    private BigDecimal temperature;
    @Column(name = "top_p")
    private BigDecimal topP;
    private Integer maxOutputTokens;
    private Integer timeoutMs;
    private boolean supportsTools;
    private boolean supportsJson;
    private String responseFormat;
    private String metadata = "{}";
    private Instant createdAt;
    private Instant updatedAt;

}
