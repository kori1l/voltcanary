package com.voltcanary.simulator.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "simulation_configs")
public class SimulationConfig {

    @Id
    private UUID id;
    @Column(name = "account_id", nullable = false)
    private UUID accountId;
    @Column(name = "device_id", nullable = false)
    private UUID deviceId;
    @Column(name = "data_source_id", nullable = false)
    private UUID dataSourceId;
    @Column(nullable = false)
    private String name;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SimulationProfile profile;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SimulationProtocol protocol;
    @Column(name = "yaml_config", nullable = false)
    private String yamlConfig;
    @Column(name = "yaml_version", nullable = false)
    private int yamlVersion;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SimulationConfigStatus status;
    @Column(name = "source_token")
    private String sourceToken;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Column(name = "created_by")
    private UUID createdBy;
    @Column(name = "updated_by")
    private UUID updatedBy;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getAccountId() { return accountId; }
    public void setAccountId(UUID accountId) { this.accountId = accountId; }
    public UUID getDeviceId() { return deviceId; }
    public void setDeviceId(UUID deviceId) { this.deviceId = deviceId; }
    public UUID getDataSourceId() { return dataSourceId; }
    public void setDataSourceId(UUID dataSourceId) { this.dataSourceId = dataSourceId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public SimulationProfile getProfile() { return profile; }
    public void setProfile(SimulationProfile profile) { this.profile = profile; }
    public SimulationProtocol getProtocol() { return protocol; }
    public void setProtocol(SimulationProtocol protocol) { this.protocol = protocol; }
    public String getYamlConfig() { return yamlConfig; }
    public void setYamlConfig(String yamlConfig) { this.yamlConfig = yamlConfig; }
    public int getYamlVersion() { return yamlVersion; }
    public void setYamlVersion(int yamlVersion) { this.yamlVersion = yamlVersion; }
    public SimulationConfigStatus getStatus() { return status; }
    public void setStatus(SimulationConfigStatus status) { this.status = status; }
    public String getSourceToken() { return sourceToken; }
    public void setSourceToken(String sourceToken) { this.sourceToken = sourceToken; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }
    public UUID getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(UUID updatedBy) { this.updatedBy = updatedBy; }
}
