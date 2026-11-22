package com.voltcanary.aiagent.domain;

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
@Table(name = "ai_model_provider_secrets")
@Getter
@Setter
public class AiModelProviderSecret {

    @Id
    private UUID id;

    private UUID providerId;

    @Enumerated(EnumType.STRING)
    private AiModelSecretType secretType;

    private String encryptedValue;
    private String encryptionKeyVersion;
    private Instant createdAt;
    private Instant rotatedAt;
}
