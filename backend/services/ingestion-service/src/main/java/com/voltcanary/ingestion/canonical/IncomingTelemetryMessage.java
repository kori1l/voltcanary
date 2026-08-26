package com.voltcanary.ingestion.canonical;

import com.voltcanary.ingestion.adapter.api.ProtocolType;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record IncomingTelemetryMessage(
        String sourceId,
        UUID deviceId,
        UUID accountId,
        Instant occurredAt,
        Instant receivedAt,
        Map<String, Object> metrics,
        TelemetryOrigin origin,
        IngestionContext context,
        boolean historicalImport) {

    public IncomingTelemetryMessage {
        receivedAt = receivedAt == null ? Instant.now() : receivedAt;
        metrics = metrics == null ? Map.of() : Map.copyOf(metrics);
        Objects.requireNonNull(origin, "origin must not be null");
        context = context == null ? IngestionContext.empty() : context;
    }

    public Instant timestamp() {
        return occurredAt;
    }

    public ProtocolType protocol() {
        return origin.protocol();
    }

    public String correlationId() {
        return context.correlationId();
    }

    public String sourceToken() {
        return context.sourceToken();
    }

    public String clientIp() {
        return context.clientIp();
    }
}
