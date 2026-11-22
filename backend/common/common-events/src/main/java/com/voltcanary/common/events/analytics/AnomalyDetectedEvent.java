package com.voltcanary.common.events.analytics;

import java.time.Instant;
import java.util.UUID;

public record AnomalyDetectedEvent(
    UUID eventId,
    UUID deviceId,
    UUID anomalyId,
    String ruleId,
    Instant windowStart,
    Instant windowEnd,
    String inputHash,
    String type,
    String severity,
    double score,
    String description,
    Instant detectedAt,
    String eventVersion,
    String correlationId,
    String producer,
    UUID accountId,
    Instant occurredAt,
    VariableRuleEvidence variableEvidence
) {
    public AnomalyDetectedEvent(
        UUID eventId,
        UUID deviceId,
        UUID anomalyId,
        String ruleId,
        Instant windowStart,
        Instant windowEnd,
        String inputHash,
        String type,
        String severity,
        double score,
        String description,
        Instant detectedAt,
        String eventVersion,
        String correlationId,
        String producer,
        UUID accountId,
        Instant occurredAt
    ) {
        this(
            eventId,
            deviceId,
            anomalyId,
            ruleId,
            windowStart,
            windowEnd,
            inputHash,
            type,
            severity,
            score,
            description,
            detectedAt,
            eventVersion,
            correlationId,
            producer,
            accountId,
            occurredAt,
            null
        );
    }

    public AnomalyDetectedEvent(
        UUID eventId,
        UUID deviceId,
        UUID anomalyId,
        String ruleId,
        Instant windowStart,
        Instant windowEnd,
        String inputHash,
        String type,
        String severity,
        double score,
        String description,
        Instant detectedAt
    ) {
        this(
            eventId,
            deviceId,
            anomalyId,
            ruleId,
            windowStart,
            windowEnd,
            inputHash,
            type,
            severity,
            score,
            description,
            detectedAt,
            "v1",
            null,
            null,
            null,
            detectedAt
        );
    }

    public AnomalyDetectedEvent(
        UUID eventId,
        UUID deviceId,
        UUID anomalyId,
        String type,
        String severity,
        double score,
        String description,
        Instant detectedAt
    ) {
        this(
            eventId,
            deviceId,
            anomalyId,
            null,
            null,
            null,
            null,
            type,
            severity,
            score,
            description,
            detectedAt
        );
    }
}
