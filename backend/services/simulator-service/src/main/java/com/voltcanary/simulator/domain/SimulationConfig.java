package com.voltcanary.simulator.domain;

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
@Table(name = "simulation_configs")
@Getter
@Setter
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
}
