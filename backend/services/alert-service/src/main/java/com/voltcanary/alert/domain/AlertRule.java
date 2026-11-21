package com.voltcanary.alert.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "alert_rules")
@Getter
@Setter
public class AlertRule {

    @Id
    private UUID id;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description")
    private String description;

    @Column(name = "rule_type", nullable = false)
    private String ruleType;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "scope_type", nullable = false)
    private String scopeType;

    @Column(name = "scope_id")
    private UUID scopeId;

    @Column(name = "device_type")
    private String deviceType;

    @Column(name = "metric", nullable = false)
    private String metric;

    @Column(name = "condition_type", nullable = false)
    private String conditionType;

    @Column(name = "threshold")
    private BigDecimal threshold;

    @Column(name = "threshold_unit")
    private String thresholdUnit;

    @Column(name = "baseline_multiplier")
    private BigDecimal baselineMultiplier;

    @Column(name = "min_points")
    private Integer minPoints;

    @Column(name = "severity", nullable = false)
    private String severity;

    @Column(name = "cooldown_seconds", nullable = false)
    private int cooldownSeconds;

    @Column(name = "deduplication_window_seconds", nullable = false)
    private int deduplicationWindowSeconds;

    @Column(name = "notification_channel_ids_json", nullable = false, columnDefinition = "text")
    private String notificationChannelIdsJson;

    @Column(name = "config_json", nullable = false, columnDefinition = "text")
    private String configJson;

    @Column(name = "version", nullable = false)
    private int version;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

}
