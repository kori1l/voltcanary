package com.voltcanary.device.analyticsconfig;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "device_analytics_configs")
public class DeviceAnalyticsConfig {

    @Id
    private UUID id;

    @Column(name = "device_id", nullable = false, unique = true)
    private UUID deviceId;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "config_json", columnDefinition = "text")
    private String configJson;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
