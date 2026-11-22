package com.voltcanary.ingestion.idempotency;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voltcanary.common.events.telemetry.TelemetryReceivedEvent;
import com.voltcanary.vcl.VclCanonical;
import java.util.List;
import java.util.TreeSet;

final class TelemetryIdentity {

    private TelemetryIdentity() {}

    static String key(ObjectMapper mapper, TelemetryReceivedEvent event) {
        ObjectNode value = mapper.valueToTree(event);
        value.remove(List.of("eventId", "receivedAt", "correlationId"));
        return "telemetry:ingestion:v2:" + VclCanonical.sha256(ordered(value).toString());
    }

    // Sort nested keys without converting exact numeric values through IEEE-754/JCS.
    private static JsonNode ordered(JsonNode value) {
        if (value.isObject()) {
            var result = JsonNodeFactory.instance.objectNode();
            var keys = new TreeSet<String>();
            value.fieldNames().forEachRemaining(keys::add);
            keys.forEach(key -> result.set(key, ordered(value.get(key))));
            return result;
        }
        if (value.isArray()) {
            var result = JsonNodeFactory.instance.arrayNode();
            value.forEach(item -> result.add(ordered(item)));
            return result;
        }
        if (value.isNumber()) {
            return com.fasterxml.jackson.databind.node.DecimalNode.valueOf(
                value.decimalValue().stripTrailingZeros()
            );
        }
        return value;
    }
}
