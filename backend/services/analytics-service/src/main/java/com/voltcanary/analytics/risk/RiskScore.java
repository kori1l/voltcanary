package com.voltcanary.analytics.risk;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "risk_scores")
public class RiskScore {

    @Id
    private UUID id;

    @Column(name = "device_id", nullable = false)
    private UUID deviceId;

    @Column(name = "score", nullable = false)
    private double score;

    @Column(name = "level", nullable = false)
    private String level;

    @Column(name = "calculated_at", nullable = false)
    private Instant calculatedAt;
}
