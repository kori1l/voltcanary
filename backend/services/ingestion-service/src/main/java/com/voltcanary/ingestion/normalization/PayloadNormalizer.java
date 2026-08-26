package com.voltcanary.ingestion.normalization;

import com.voltcanary.ingestion.canonical.IncomingTelemetryMessage;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class PayloadNormalizer {

    public IncomingTelemetryMessage normalize(IncomingTelemetryMessage message) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        message.metrics().forEach((key, value) -> metrics.put(key.trim(), normalizeValue(value)));
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
