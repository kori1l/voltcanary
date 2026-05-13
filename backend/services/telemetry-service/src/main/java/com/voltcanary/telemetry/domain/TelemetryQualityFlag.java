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
}
