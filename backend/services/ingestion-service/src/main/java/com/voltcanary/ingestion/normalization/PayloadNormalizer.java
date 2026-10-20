package com.voltcanary.ingestion.normalization;

import com.voltcanary.ingestion.canonical.IncomingTelemetryMessage;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class PayloadNormalizer {
    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();

    public IncomingTelemetryMessage normalize(IncomingTelemetryMessage message) {
        return normalize(message, Map.of());
    }

    public IncomingTelemetryMessage normalize(IncomingTelemetryMessage message, Map<String, Object> mapping) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        for (var entry : message.metrics().entrySet()) metrics.put(entry.getKey().trim(), normalizeValue(entry.getValue()));
        if (mapping != null && mapping.containsKey("telemetryPolicy")) {
            try {
                var policy = MAPPER.convertValue(mapping.get("telemetryPolicy"), com.voltcanary.common.events.telemetry.TelemetryFieldPolicy.class);
                metrics = new LinkedHashMap<>(policy.select(metrics));
            } catch (IllegalArgumentException invalid) {
                throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "Invalid configured telemetry field value", invalid);
            }
        }
        return new IncomingTelemetryMessage(
                message.sourceId().trim(),
                message.deviceId(),
                message.accountId(),
                message.occurredAt(),
                message.receivedAt(),
                Map.copyOf(metrics),
                message.origin(),
                message.context(),
                message.historicalImport());
    }

    private Object normalizeValue(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal.doubleValue();
        }
        return value;
    }
}
