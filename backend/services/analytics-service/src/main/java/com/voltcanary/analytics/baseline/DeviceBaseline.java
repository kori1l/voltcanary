package com.voltcanary.analytics.baseline;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "device_baselines")
public class DeviceBaseline {

    @Id
    private UUID id;

    @Column(name = "device_id", nullable = false)
    private UUID deviceId;

    @Column(name = "baseline_json", nullable = false, columnDefinition = "text")
    private String baselineJson;

    @Column(name = "calculated_at", nullable = false)
    private Instant calculatedAt;
}
