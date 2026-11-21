package com.voltcanary.device.domain;

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
@Table(name = "data_sources")
@Getter
@Setter
public class DataSource {

    @Id
    private UUID id;

    @Column(name = "device_id", nullable = false)
    private UUID deviceId;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DataSourceType type;

    @Column(name = "connection_ref")
    private String connectionRef;

    @Column(name = "source_id", nullable = false)
    private String sourceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false)
    private DataSourceType sourceType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DataSourceProtocol protocol;

    @Column(nullable = false)
    private boolean enabled;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SourceStatus status;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    @Column(name = "credentials_ref")
    private String credentialsRef;

    @Column(name = "mapping_json")
    private String mappingJson;

    @Column(name = "adapter_config_json")
    private String adapterConfigJson;

    @Column(name = "credential_references_json")
    private String credentialReferencesJson;

    @Column(name = "home_region")
    private String homeRegion;

    @Column(name = "ingestion_token_hash")
    private String ingestionTokenHash;

    @Column(name = "token_prefix")
    private String tokenPrefix;

    @Column(name = "token_created_at")
    private Instant tokenCreatedAt;

    @Column(name = "token_last_used_at")
    private Instant tokenLastUsedAt;

    @Column(name = "token_revoked_at")
    private Instant tokenRevokedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

}
