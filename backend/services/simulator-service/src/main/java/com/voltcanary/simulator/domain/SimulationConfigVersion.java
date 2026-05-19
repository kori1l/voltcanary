package com.voltcanary.simulator.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "simulation_config_versions")
public class SimulationConfigVersion {
    @Id
    private UUID id;
    @Column(name = "config_id", nullable = false)
    private UUID configId;
    @Column(nullable = false)
    private int version;
    @Column(name = "yaml_config", nullable = false)
    private String yamlConfig;
    @Column(name = "change_note")
    private String changeNote;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "created_by")
    private UUID createdBy;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getConfigId() { return configId; }
    public void setConfigId(UUID configId) { this.configId = configId; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public String getYamlConfig() { return yamlConfig; }
    public void setYamlConfig(String yamlConfig) { this.yamlConfig = yamlConfig; }
    public String getChangeNote() { return changeNote; }
    public void setChangeNote(String changeNote) { this.changeNote = changeNote; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }
}
