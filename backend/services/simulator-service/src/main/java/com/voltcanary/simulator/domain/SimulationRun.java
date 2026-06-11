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
@Table(name = "simulation_runs")
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

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getConfigId() { return configId; }
    public void setConfigId(UUID configId) { this.configId = configId; }
    public UUID getAccountId() { return accountId; }
    public void setAccountId(UUID accountId) { this.accountId = accountId; }
    public UUID getDeviceId() { return deviceId; }
    public void setDeviceId(UUID deviceId) { this.deviceId = deviceId; }
    public UUID getDataSourceId() { return dataSourceId; }
    public void setDataSourceId(UUID dataSourceId) { this.dataSourceId = dataSourceId; }
    public SimulationRunStatus getStatus() { return status; }
    public void setStatus(SimulationRunStatus status) { this.status = status; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getStoppedAt() { return stoppedAt; }
    public void setStoppedAt(Instant stoppedAt) { this.stoppedAt = stoppedAt; }
    public UUID getStartedBy() { return startedBy; }
    public void setStartedBy(UUID startedBy) { this.startedBy = startedBy; }
    public String getStopReason() { return stopReason; }
    public void setStopReason(String stopReason) { this.stopReason = stopReason; }
    public long getGeneratedPoints() { return generatedPoints; }
    public void setGeneratedPoints(long generatedPoints) { this.generatedPoints = generatedPoints; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public String getActiveKey() { return activeKey; }
    public void setActiveKey(String activeKey) { this.activeKey = activeKey; }
    public String getLeaseOwner() { return leaseOwner; }
    public void setLeaseOwner(String leaseOwner) { this.leaseOwner = leaseOwner; }
    public Instant getLeaseUntil() { return leaseUntil; }
    public void setLeaseUntil(Instant leaseUntil) { this.leaseUntil = leaseUntil; }
}
