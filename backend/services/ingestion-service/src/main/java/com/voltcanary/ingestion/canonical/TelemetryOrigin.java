package com.voltcanary.ingestion.canonical;

import com.voltcanary.ingestion.adapter.api.AdapterType;
import com.voltcanary.ingestion.adapter.api.ProtocolType;
import com.voltcanary.ingestion.adapter.api.TransportType;
import java.util.Map;
import java.util.Objects;

public record TelemetryOrigin(
        AdapterType adapterType,
        ProtocolType protocol,
        TransportType transport,
        String adapterId,
        Map<String, String> metadata) {

    public TelemetryOrigin {
        Objects.requireNonNull(adapterType, "adapterType must not be null");
        Objects.requireNonNull(protocol, "protocol must not be null");
        Objects.requireNonNull(transport, "transport must not be null");
        adapterId = adapterId == null || adapterId.isBlank()
                ? adapterType.name().toLowerCase()
                : adapterId.trim();
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static TelemetryOrigin httpPush() {
        return new TelemetryOrigin(
                AdapterType.GENERIC_HTTP_PUSH,
                ProtocolType.REST,
                TransportType.HTTP,
                "generic-http-push",
                Map.of());
    }

    public static TelemetryOrigin mqtt(String topic) {
        Map<String, String> metadata = topic == null || topic.isBlank()
                ? Map.of()
                : Map.of("topic", topic);
        return new TelemetryOrigin(
                AdapterType.GENERIC_MQTT,
                ProtocolType.MQTT,
                TransportType.MQTT,
                "generic-mqtt",
                metadata);
    }
}
