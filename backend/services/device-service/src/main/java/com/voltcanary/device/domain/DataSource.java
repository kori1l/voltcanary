package com.voltcanary.device.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "data_sources")
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

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(UUID deviceId) {
        this.deviceId = deviceId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public DataSourceType getType() {
        return type;
    }

    public void setType(DataSourceType type) {
        this.type = type;
    }

    public String getConnectionRef() {
        return connectionRef;
    }

    public void setConnectionRef(String connectionRef) {
        this.connectionRef = connectionRef;
    }

    public String getSourceId() {
        return sourceId;
    }

    public void setSourceId(String sourceId) {
        this.sourceId = sourceId;
    }

    public DataSourceType getSourceType() {
        return sourceType;
    }

    public void setSourceType(DataSourceType sourceType) {
        this.sourceType = sourceType;
    }

    public DataSourceProtocol getProtocol() {
        return protocol;
    }

    public void setProtocol(DataSourceProtocol protocol) {
        this.protocol = protocol;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public SourceStatus getStatus() {
        return status;
    }

    public void setStatus(SourceStatus status) {
        this.status = status;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public void setLastSeenAt(Instant lastSeenAt) {
        this.lastSeenAt = lastSeenAt;
    }

    public String getCredentialsRef() {
        return credentialsRef;
    }

    public void setCredentialsRef(String credentialsRef) {
        this.credentialsRef = credentialsRef;
    }

    public String getMappingJson() {
        return mappingJson;
    }

    public void setMappingJson(String mappingJson) {
        this.mappingJson = mappingJson;
    }

    public String getAdapterConfigJson() {
        return adapterConfigJson;
    }

    public void setAdapterConfigJson(String adapterConfigJson) {
        this.adapterConfigJson = adapterConfigJson;
    }

    public String getCredentialReferencesJson() {
        return credentialReferencesJson;
    }

    public void setCredentialReferencesJson(String credentialReferencesJson) {
        this.credentialReferencesJson = credentialReferencesJson;
    }

    public String getHomeRegion() {
        return homeRegion;
    }

    public void setHomeRegion(String homeRegion) {
        this.homeRegion = homeRegion;
    }

    public String getIngestionTokenHash() {
        return ingestionTokenHash;
    }

    public void setIngestionTokenHash(String ingestionTokenHash) {
        this.ingestionTokenHash = ingestionTokenHash;
    }

    public String getTokenPrefix() {
        return tokenPrefix;
    }

    public void setTokenPrefix(String tokenPrefix) {
        this.tokenPrefix = tokenPrefix;
    }

    public Instant getTokenCreatedAt() {
        return tokenCreatedAt;
    }

    public void setTokenCreatedAt(Instant tokenCreatedAt) {
        this.tokenCreatedAt = tokenCreatedAt;
    }

    public Instant getTokenLastUsedAt() {
        return tokenLastUsedAt;
    }

    public void setTokenLastUsedAt(Instant tokenLastUsedAt) {
        this.tokenLastUsedAt = tokenLastUsedAt;
    }

    public Instant getTokenRevokedAt() {
        return tokenRevokedAt;
    }

    public void setTokenRevokedAt(Instant tokenRevokedAt) {
        this.tokenRevokedAt = tokenRevokedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
