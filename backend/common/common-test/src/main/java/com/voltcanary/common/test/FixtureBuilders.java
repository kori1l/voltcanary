package com.voltcanary.common.test;

import com.voltcanary.common.events.analytics.AnomalyDetectedEvent;
import com.voltcanary.common.events.analytics.RiskScoreChangedEvent;
import com.voltcanary.common.events.telemetry.TelemetryReceivedEvent;
import com.voltcanary.common.events.telemetry.TelemetryValidatedEvent;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class FixtureBuilders {

    private FixtureBuilders() {
    }

    public static TelemetryReceivedEvent telemetryReceivedEvent() {
        return new TelemetryReceivedEvent(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "test-source",
                Instant.now(),
                Map.of("power", 42.0),
                Instant.now());
    }

    public static TelemetryValidatedEvent telemetryValidatedEvent() {
        return new TelemetryValidatedEvent(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "test-source",
                Instant.now(),
                Map.of("power", 42.0),
                List.of("VALID"),
                Instant.now());
    }

    public static AnomalyDetectedEvent anomalyDetectedEvent() {
        return new AnomalyDetectedEvent(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "POWER_SPIKE",
                "WARNING",
                0.72,
                "Test anomaly",
                Instant.now());
    }

    public static RiskScoreChangedEvent riskScoreChangedEvent() {
        return new RiskScoreChangedEvent(
                UUID.randomUUID(),
                UUID.randomUUID(),
                0.2,
                0.7,
                "HIGH",
                Instant.now());
    }
}
