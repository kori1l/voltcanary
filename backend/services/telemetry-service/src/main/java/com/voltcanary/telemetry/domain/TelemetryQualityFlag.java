package com.voltcanary.telemetry.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "telemetry_quality_flags")
public class TelemetryQualityFlag {

    @Id
    private UUID id;

    @Column(name = "reading_id", nullable = false)
    private UUID readingId;

    @Column(name = "device_id", nullable = false)
    private UUID deviceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private QualityFlag flag;

    @Column(name = "message")
    private String message;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected TelemetryQualityFlag() {
    }

    public TelemetryQualityFlag(UUID id, UUID readingId, UUID deviceId, QualityFlag flag, String message, Instant createdAt) {
        this.id = id;
        this.readingId = readingId;
        this.deviceId = deviceId;
        this.flag = flag;
        this.message = message;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getReadingId() {
        return readingId;
    }

    public UUID getDeviceId() {
        return deviceId;
    }

    public QualityFlag getFlag() {
        return flag;
    }

    public String getMessage() {
        return message;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
