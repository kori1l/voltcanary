package com.voltcanary.ingestion.normalization;

import com.voltcanary.ingestion.pipeline.IncomingTelemetryMessage;
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
                message.timestamp(),
                Map.copyOf(metrics),
                message.protocol(),
                message.rawPayload(),
                message.correlationId(),
                message.sourceToken(),
                message.deviceId(),
                message.clientIp(),
                message.historicalImport());
    }

    private Object normalizeValue(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal.doubleValue();
        }
        return value;
    }
}
