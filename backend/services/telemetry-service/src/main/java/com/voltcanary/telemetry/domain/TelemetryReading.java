package com.voltcanary.telemetry.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "telemetry_readings")
public class TelemetryReading {

    @Id
    private UUID id;

    @Column(name = "device_id", nullable = false)
    private UUID deviceId;

    @Column(name = "source_id")
    private String sourceId;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "metrics_json", nullable = false, columnDefinition = "text")
    private String metricsJson;

    protected TelemetryReading() {
    }

    public TelemetryReading(UUID id, UUID deviceId, String sourceId, Instant recordedAt, Instant receivedAt, String metricsJson) {
        this.id = id;
        this.deviceId = deviceId;
        this.sourceId = sourceId;
        this.recordedAt = recordedAt;
        this.receivedAt = receivedAt;
        this.metricsJson = metricsJson;
    }

    public UUID getId() {
        return id;
    }

    public UUID getDeviceId() {
        return deviceId;
    }

    public String getSourceId() {
        return sourceId;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public String getMetricsJson() {
        return metricsJson;
    }
}
