package com.voltcanary.aiagent.provider;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ai_model_configs")
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

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getProviderId() { return providerId; }
    public void setProviderId(UUID providerId) { this.providerId = providerId; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getModelId() { return modelId; }
    public void setModelId(String modelId) { this.modelId = modelId; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isDefaultModel() { return defaultModel; }
    public void setDefaultModel(boolean defaultModel) { this.defaultModel = defaultModel; }
    public BigDecimal getTemperature() { return temperature; }
    public void setTemperature(BigDecimal temperature) { this.temperature = temperature; }
    public BigDecimal getTopP() { return topP; }
    public void setTopP(BigDecimal topP) { this.topP = topP; }
    public Integer getMaxOutputTokens() { return maxOutputTokens; }
    public void setMaxOutputTokens(Integer maxOutputTokens) { this.maxOutputTokens = maxOutputTokens; }
    public Integer getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(Integer timeoutMs) { this.timeoutMs = timeoutMs; }
    public boolean isSupportsTools() { return supportsTools; }
    public void setSupportsTools(boolean supportsTools) { this.supportsTools = supportsTools; }
    public boolean isSupportsJson() { return supportsJson; }
    public void setSupportsJson(boolean supportsJson) { this.supportsJson = supportsJson; }
    public String getResponseFormat() { return responseFormat; }
    public void setResponseFormat(String responseFormat) { this.responseFormat = responseFormat; }
    public String getMetadata() { return metadata; }
    public void setMetadata(String metadata) { this.metadata = metadata; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
