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
@Table(name = "device_baselines")
@Getter
@Setter
public class DeviceBaseline {

    @Id
    private UUID id;

    @Column(name = "device_id", nullable = false)
    private UUID deviceId;

    @Column(name = "baseline_json", nullable = false, columnDefinition = "text")
    private String baselineJson;

    @Column(name = "metric")
    private String metric;

    @Column(name = "period")
    private String period;

    @Column(name = "algorithm_version")
    private String algorithmVersion;

    @Column(name = "mean")
    private Double mean;

    @Column(name = "median")
    private Double median;

    @Column(name = "p90")
    private Double p90;

    @Column(name = "min")
    private Double min;

    @Column(name = "max")
    private Double max;

    @Column(name = "sample_count")
    private Integer sampleCount;

    @Column(name = "calculated_at", nullable = false)
    private Instant calculatedAt;

}
