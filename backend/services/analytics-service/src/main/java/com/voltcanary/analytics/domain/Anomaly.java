package com.voltcanary.analytics.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "anomalies")
@Getter
@Setter
public class Anomaly {

    @Id
    private UUID id;

    @Column(name = "device_id", nullable = false)
    private UUID deviceId;

    @Column(name = "account_id")
    private UUID accountId;

    @Column(name = "type", nullable = false)
    private String type;

    @Column(name = "severity", nullable = false)
    private String severity;

    @Column(name = "score", nullable = false)
    private double score;

    @Column(name = "description", nullable = false)
    private String description;

    @Column(name = "rule_id")
    private String ruleId;

    @Column(name = "rule_version")
    private String ruleVersion;

    @Column(name = "window_start")
    private Instant windowStart;

    @Column(name = "window_end")
    private Instant windowEnd;

    @Column(name = "input_hash")
    private String inputHash;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "detected_at", nullable = false)
    private Instant detectedAt;

    @Column(name = "superseded_at")
    private Instant supersededAt;

}
