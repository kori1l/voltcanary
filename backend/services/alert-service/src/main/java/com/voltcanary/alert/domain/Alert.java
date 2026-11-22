package com.voltcanary.alert.domain;

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
@Table(name = "alerts")
@Getter
@Setter
public class Alert {

    @Id
    private UUID id;

    @Column(name = "device_id", nullable = false)
    private UUID deviceId;

    @Column(name = "account_id")
    private UUID accountId;

    @Column(name = "source_event_id")
    private UUID sourceEventId;

    @Column(name = "anomaly_id")
    private UUID anomalyId;

    @Column(name = "risk_score_id")
    private UUID riskScoreId;

    @Column(name = "rule_id")
    private String ruleId;

    @Column(name = "calculation_window_start")
    private Instant calculationWindowStart;

    @Column(name = "calculation_window_end")
    private Instant calculationWindowEnd;

    @Column(name = "calculation_input_hash")
    private String calculationInputHash;

    @Column(name = "deduplication_key", nullable = false)
    private String deduplicationKey;

    @Column(name = "alert_type")
    private String alertType;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "description", nullable = false)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false)
    private AlertSeverity severity;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private AlertStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;

    @Column(name = "acknowledged_by")
    private UUID acknowledgedBy;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "resolved_by")
    private UUID resolvedBy;

    @Column(name = "assigned_to")
    private UUID assignedTo;

    @Column(name = "resolution_reason")
    private String resolutionReason;
}
