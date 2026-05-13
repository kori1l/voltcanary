package com.voltcanary.analytics.feature;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "feature_sets")
public class FeatureSet {

    @Id
    private UUID id;

    @Column(name = "device_id", nullable = false)
    private UUID deviceId;

    @Column(name = "window_start", nullable = false)
    private Instant windowStart;

    @Column(name = "window_end", nullable = false)
    private Instant windowEnd;

    @Column(name = "features_json", nullable = false, columnDefinition = "text")
    private String featuresJson;
}
