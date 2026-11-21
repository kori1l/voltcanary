package com.voltcanary.simulator.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "simulation_config_versions")
@Getter
@Setter
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

}
