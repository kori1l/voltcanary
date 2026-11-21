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
@Table(name = "maintenance_windows_read_model")
@Getter
@Setter
public class MaintenanceWindowReadModel {

    @Id
    @Column(name = "maintenance_window_id")
    private UUID maintenanceWindowId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "device_id", nullable = false)
    private UUID deviceId;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "start_at", nullable = false)
    private Instant startAt;

    @Column(name = "end_at", nullable = false)
    private Instant endAt;

    @Column(name = "suppress_alerts", nullable = false)
    private boolean suppressAlerts;

    @Column(name = "exclude_from_analytics", nullable = false)
    private boolean excludeFromAnalytics;

    @Column(name = "exclude_from_ml", nullable = false)
    private boolean excludeFromMl;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

}
