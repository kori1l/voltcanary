package com.voltcanary.analytics.anomaly;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "anomalies")
public class Anomaly {

    @Id
    private UUID id;

    @Column(name = "device_id", nullable = false)
    private UUID deviceId;

    @Column(name = "severity", nullable = false)
    private String severity;

    @Column(name = "score", nullable = false)
    private double score;

    @Column(name = "description", nullable = false)
    private String description;

    @Column(name = "detected_at", nullable = false)
    private Instant detectedAt;
}
