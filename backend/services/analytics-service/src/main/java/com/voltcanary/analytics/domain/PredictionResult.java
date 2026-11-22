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
@Table(name = "prediction_results")
@Getter
@Setter
public class PredictionResult {

    @Id
    private UUID id;

    @Column(name = "device_id", nullable = false)
    private UUID deviceId;

    @Column(name = "model_name", nullable = false)
    private String modelName;

    @Column(name = "model_version")
    private String modelVersion;

    @Column(name = "anomaly_score")
    private Double anomalyScore;

    @Column(name = "degradation_score")
    private Double degradationScore;

    @Column(name = "confidence")
    private Double confidence;

    @Column(name = "risk_level")
    private String riskLevel;

    @Column(name = "status")
    private String status;

    @Column(name = "input_hash")
    private String inputHash;

    @Column(name = "model_id")
    private UUID modelId;

    @Column(name = "policy_source")
    private String policySource;

    @Column(name = "feature_schema_hash")
    private String featureSchemaHash;

    @Column(name = "fallback_reason")
    private String fallbackReason;

    @Column(name = "shadow_prediction", nullable = false)
    private boolean shadowPrediction;

    @Column(name = "prediction_json", nullable = false, columnDefinition = "text")
    private String predictionJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
