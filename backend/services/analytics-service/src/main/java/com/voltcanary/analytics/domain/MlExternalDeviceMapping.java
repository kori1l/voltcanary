package com.voltcanary.analytics.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ml_external_device_mappings")
public class MlExternalDeviceMapping {
    @Id
    private UUID id;
    @Column(name = "account_id", nullable = false)
    private UUID accountId;
    @Column(name = "imported_file_id", nullable = false)
    private UUID importedFileId;
    @Column(name = "external_device_key", nullable = false)
    private String externalDeviceKey;
    @Column(name = "matched_device_id")
    private UUID matchedDeviceId;
    @Column(name = "virtual_device_key", nullable = false)
    private String virtualDeviceKey;
    @Column(name = "device_type")
    private String deviceType;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getAccountId() { return accountId; }
    public void setAccountId(UUID accountId) { this.accountId = accountId; }
    public UUID getImportedFileId() { return importedFileId; }
    public void setImportedFileId(UUID importedFileId) { this.importedFileId = importedFileId; }
    public String getExternalDeviceKey() { return externalDeviceKey; }
    public void setExternalDeviceKey(String externalDeviceKey) { this.externalDeviceKey = externalDeviceKey; }
    public UUID getMatchedDeviceId() { return matchedDeviceId; }
    public void setMatchedDeviceId(UUID matchedDeviceId) { this.matchedDeviceId = matchedDeviceId; }
    public String getVirtualDeviceKey() { return virtualDeviceKey; }
    public void setVirtualDeviceKey(String virtualDeviceKey) { this.virtualDeviceKey = virtualDeviceKey; }
    public String getDeviceType() { return deviceType; }
    public void setDeviceType(String deviceType) { this.deviceType = deviceType; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
