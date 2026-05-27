package com.voltcanary.aiagent.provider;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ai_model_providers")
public class AiModelProvider {
    @Id
    private UUID id;
    private UUID accountId;
    private UUID createdByUserId;
    private String name;
    @Enumerated(EnumType.STRING)
    private AiModelProviderType providerType;
    @Enumerated(EnumType.STRING)
    private AiModelProviderStatus status;
    @Column(name = "is_default")
    private boolean defaultProvider;
    private String baseUrl;
    @Enumerated(EnumType.STRING)
    private AiModelAuthType authType;
    private String maskedSecretHint;
    private String lastTestStatus;
    private String lastTestMessage;
    private Instant lastTestAt;
    private String metadata = "{}";
    private Instant createdAt;
    private Instant updatedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getAccountId() { return accountId; }
    public void setAccountId(UUID accountId) { this.accountId = accountId; }
    public UUID getCreatedByUserId() { return createdByUserId; }
    public void setCreatedByUserId(UUID createdByUserId) { this.createdByUserId = createdByUserId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public AiModelProviderType getProviderType() { return providerType; }
    public void setProviderType(AiModelProviderType providerType) { this.providerType = providerType; }
    public AiModelProviderStatus getStatus() { return status; }
    public void setStatus(AiModelProviderStatus status) { this.status = status; }
    public boolean isDefaultProvider() { return defaultProvider; }
    public void setDefaultProvider(boolean defaultProvider) { this.defaultProvider = defaultProvider; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public AiModelAuthType getAuthType() { return authType; }
    public void setAuthType(AiModelAuthType authType) { this.authType = authType; }
    public String getMaskedSecretHint() { return maskedSecretHint; }
    public void setMaskedSecretHint(String maskedSecretHint) { this.maskedSecretHint = maskedSecretHint; }
    public String getLastTestStatus() { return lastTestStatus; }
    public void setLastTestStatus(String lastTestStatus) { this.lastTestStatus = lastTestStatus; }
    public String getLastTestMessage() { return lastTestMessage; }
    public void setLastTestMessage(String lastTestMessage) { this.lastTestMessage = lastTestMessage; }
    public Instant getLastTestAt() { return lastTestAt; }
    public void setLastTestAt(Instant lastTestAt) { this.lastTestAt = lastTestAt; }
    public String getMetadata() { return metadata; }
    public void setMetadata(String metadata) { this.metadata = metadata; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
