package com.voltcanary.telemetry.service;

import com.voltcanary.telemetry.config.TelemetryValidationProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TelemetryIngestionRateLimiterTest {

    @Test
    void limitsEventsPerDeviceWithinMinuteWindow() {
        TelemetryValidationProperties properties = new TelemetryValidationProperties();
        properties.setMaxEventsPerDevicePerMinute(2);
        TelemetryIngestionRateLimiter limiter = new TelemetryIngestionRateLimiter(
                properties,
                Clock.fixed(Instant.parse("2026-05-12T12:30:10Z"), ZoneOffset.UTC));
        UUID deviceId = UUID.randomUUID();

        assertThat(limiter.allow(deviceId)).isTrue();
        assertThat(limiter.allow(deviceId)).isTrue();
        assertThat(limiter.allow(deviceId)).isFalse();
    }
}
