package com.voltcanary.ingestion.normalization;

import com.voltcanary.ingestion.canonical.IncomingTelemetryMessage;
import com.voltcanary.vcl.VclSourceNormalization;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class PayloadNormalizer {
    public IncomingTelemetryMessage normalize(IncomingTelemetryMessage message) {
        return normalize(message, Map.of());
    }

    public IncomingTelemetryMessage normalize(IncomingTelemetryMessage message, Map<String, Object> mapping) {
        Map<String, Object> metrics;
        try {
            metrics = VclSourceNormalization.normalize(message.metrics(), mapping);
        } catch (IllegalArgumentException invalid) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "Invalid configured telemetry field value", invalid);
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
                message.historicalImport(), message.sourcePosition());
    }

}
