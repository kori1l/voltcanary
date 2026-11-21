package com.voltcanary.alert.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "notification_preferences")
@Getter
@Setter
public class NotificationPreference {

    @Id
    private UUID id;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "configurable_rule_alerts_enabled", nullable = false)
    private boolean configurableRuleAlertsEnabled;

    @Column(name = "severity_filter", nullable = false)
    private String severityFilter;

    @Column(name = "channel_type_filter", nullable = false)
    private String channelTypeFilter;

    @Column(name = "device_filter", nullable = false)
    private String deviceFilter;

    @Column(name = "quiet_hours_start")
    private LocalTime quietHoursStart;

    @Column(name = "quiet_hours_end")
    private LocalTime quietHoursEnd;

    @Column(name = "timezone", nullable = false)
    private String timezone;

    @Column(name = "critical_bypass_quiet_hours", nullable = false)
    private boolean criticalBypassQuietHours;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

}
