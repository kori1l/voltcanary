package com.voltcanary.alert.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "alert_suppressions")
@Getter
@Setter
public class AlertSuppression {

    @Id
    private UUID id;

    @Column(name = "device_id", nullable = false)
    private UUID deviceId;

    @Column(name = "alert_id")
    private UUID alertId;

    @Column(name = "source_event_id")
    private UUID sourceEventId;

    @Column(name = "source_event_type", nullable = false)
    private String sourceEventType;

    @Column(name = "maintenance_window_id", nullable = false)
    private UUID maintenanceWindowId;

    @Column(name = "maintenance_window_version", nullable = false)
    private long maintenanceWindowVersion;

    @Column(name = "suppression_reason", nullable = false)
    private String suppressionReason;

    @Column(name = "event_time", nullable = false)
    private Instant eventTime;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

}
