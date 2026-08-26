package com.voltcanary.ingestion;

import com.voltcanary.ingestion.adapter.api.ProtocolType;
import com.voltcanary.ingestion.canonical.IncomingTelemetryMessage;
import com.voltcanary.ingestion.canonical.TelemetryOrigin;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CanonicalTelemetryMessageTest {

    @Test
    void canonicalMessageCarriesOriginContextAndReceivedTime() {
        Instant occurredAt = Instant.parse("2026-05-19T12:30:00Z");

        IncomingTelemetryMessage message = new IncomingTelemetryMessage(
                "plug-001",
                null,
                null,
                occurredAt,
                null,
                Map.of("activePowerW", 120.5),
                TelemetryOrigin.mqtt("voltcanary/sources/plug-001/telemetry"),
                null,
                false);

        assertThat(message.occurredAt()).isEqualTo(occurredAt);
        assertThat(message.receivedAt()).isNotNull();
        assertThat(message.origin().protocol()).isEqualTo(ProtocolType.MQTT);
        assertThat(message.origin().metadata()).containsEntry("topic", "voltcanary/sources/plug-001/telemetry");
        assertThat(message.context().metadata()).isEmpty();
    }
}
