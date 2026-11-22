package com.voltcanary.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.voltcanary.common.events.telemetry.ConfiguredFields;
import com.voltcanary.ingestion.canonical.IncomingTelemetryMessage;
import com.voltcanary.ingestion.canonical.TelemetryOrigin;
import com.voltcanary.ingestion.normalization.PayloadNormalizer;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class ReviewedMappingNormalizationTest {

    @Test
    void reviewedTokensProduceTypedValuesWithoutChangingLegacyIdentityMappings() {
        var normalizer = new PayloadNormalizer();
        assertEquals(
            Boolean.TRUE,
            ConfiguredFields.values(
                normalizer.normalize(message("open"), mapping("boolean", true)).metrics()
            )
                .get("door")
                .get("value")
        );
        assertEquals(
            Boolean.FALSE,
            ConfiguredFields.values(
                normalizer.normalize(message("closed"), mapping("boolean", true)).metrics()
            )
                .get("door")
                .get("value")
        );
        assertEquals(
            "open",
            ConfiguredFields.values(
                normalizer.normalize(message("open"), mapping("string", false)).metrics()
            )
                .get("door")
                .get("value")
        );
        assertThrows(ResponseStatusException.class, () ->
            normalizer.normalize(message("OPEN"), mapping("boolean", true))
        );
    }

    private static IncomingTelemetryMessage message(String token) {
        return new IncomingTelemetryMessage(
            "fixture",
            UUID.randomUUID(),
            UUID.randomUUID(),
            Instant.EPOCH,
            Instant.EPOCH,
            Map.of("switch", Map.of("state", token)),
            TelemetryOrigin.httpPush(),
            null,
            false
        );
    }

    private static Map<String, Object> mapping(String type, boolean reviewed) {
        var policy = Map.of(
            "fields",
            List.of(
                Map.of(
                    "path",
                    "switch.state",
                    "key",
                    "door",
                    "label",
                    "Door",
                    "type",
                    type,
                    "unit",
                    "1",
                    "store",
                    true,
                    "display",
                    false
                )
            )
        );
        if (!reviewed) return Map.of("telemetryPolicy", policy);
        return Map.of(
            "telemetryPolicy",
            policy,
            "reviewedBooleanMappings",
            Map.of(
                "door",
                Map.of(
                    "method",
                    "parse_boolean",
                    "trueTokens",
                    List.of("open"),
                    "falseTokens",
                    List.of("closed")
                )
            )
        );
    }
}
