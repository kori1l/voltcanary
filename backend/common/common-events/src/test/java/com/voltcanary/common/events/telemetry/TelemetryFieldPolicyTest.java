package com.voltcanary.common.events.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TelemetryFieldPolicyTest {

    private TelemetryFieldPolicy.Field field(String path, String type, boolean store) {
        return new TelemetryFieldPolicy.Field(path, path, path, type, "", store, true);
    }

    @Test
    void selectsOnlyRequestedValuesWithTypesAndNoOriginalPayloadLeak() {
        var policy = new TelemetryFieldPolicy(
            List.of(
                field("speedKmh", "number", false),
                field("sensor.count", "number", true),
                field("transport.doorsOpen", "boolean", true),
                field("transport.destination", "string", true)
            )
        );
        var selected = policy.select(
            Map.of(
                "speedKmh",
                42,
                "sensor",
                Map.of("count", 0, "secretExtra", 9),
                "transport",
                Map.of("doorsOpen", false, "destination", "Airport", "accelerationMs2", 1)
            )
        );
        assertThat(selected.keySet()).containsExactly("configuredFields");
        var fields = ConfiguredFields.values(selected);
        assertThat(fields.keySet()).containsExactly(
            "sensor.count",
            "transport.doorsOpen",
            "transport.destination"
        );
        assertThat(fields.get("sensor.count").get("value")).isEqualTo(0);
        assertThat(fields.get("transport.doorsOpen").get("value")).isEqualTo(false);
        assertThatThrownBy(() ->
            policy.select(Map.of("sensor", Map.of("count", "wrong")))
        ).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void enforcesAtomicCoordinatesSafePathsAndTypedCanonicalNames() {
        assertThatThrownBy(() ->
            new TelemetryFieldPolicy(List.of(field("latitude", "number", true)))
        ).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
            new TelemetryFieldPolicy(List.of(field("sensor[*]", "number", true)))
        ).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
            new TelemetryFieldPolicy(List.of(field("speedKmh", "string", true)))
        ).isInstanceOf(IllegalArgumentException.class);
        var policy = new TelemetryFieldPolicy(
            List.of(field("latitude", "number", true), field("longitude", "number", true))
        );
        assertThat(
            GpsMetrics.valid(policy.select(Map.of("latitude", 60, "longitude", 25)))
        ).isTrue();
        assertThat(GpsMetrics.valid(policy.select(Map.of("latitude", 60)))).isFalse();
        assertThat(new TelemetryFieldPolicy(List.of()).select(Map.of("discard", 10))).isEmpty();
    }
}
