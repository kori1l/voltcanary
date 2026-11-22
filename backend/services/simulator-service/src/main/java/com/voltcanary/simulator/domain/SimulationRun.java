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
@Table(name = "simulation_runs")
@Getter
@Setter
public class SimulationRun {

    @Id
    private UUID id;

    @Column(name = "config_id", nullable = false)
    private UUID configId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "device_id", nullable = false)
    private UUID deviceId;

    @Column(name = "data_source_id", nullable = false)
    private UUID dataSourceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SimulationRunStatus status;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "stopped_at")
    private Instant stoppedAt;

    @Column(name = "started_by")
    private UUID startedBy;

    @Column(name = "stop_reason")
    private String stopReason;

    @Column(name = "generated_points", nullable = false)
    private long generatedPoints;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "active_key")
    private String activeKey;

    @Column(name = "lease_owner")
    private String leaseOwner;

    @Column(name = "lease_until")
    private Instant leaseUntil;
}
