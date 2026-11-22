package com.voltcanary.aiagent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "ai_model_providers")
@Getter
@Setter
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
}
