package com.voltcanary.ingestion.adapter.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.ingestion.adapter.api.SourceRuntimeConfig;
import com.voltcanary.ingestion.canonical.IngestionContext;
import com.voltcanary.ingestion.canonical.IncomingTelemetryMessage;
import com.voltcanary.ingestion.canonical.TelemetryOrigin;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class HttpPollingResponseMapper {

    private final ObjectMapper objectMapper;
    private final Clock clock;

    public HttpPollingResponseMapper(ObjectMapper objectMapper, Clock clock) {
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    IncomingTelemetryMessage map(
            SourceRuntimeConfig sourceRuntimeConfig,
            String body,
            int statusCode,
            URI endpoint,
            TelemetryOrigin origin) {
        try {
            JsonNode root = objectMapper.readTree(body);
            Instant receivedAt = clock.instant();
            Instant occurredAt = timestamp(root, sourceRuntimeConfig.mapping(), receivedAt);
            Map<String, Object> metrics = metrics(root, sourceRuntimeConfig.mapping());
            if (metrics.isEmpty()) {
                throw new IllegalArgumentException("HTTP polling response produced no mapped metrics");
            }
            return new IncomingTelemetryMessage(
                    sourceRuntimeConfig.sourceId(),
                    sourceRuntimeConfig.deviceId(),
                    sourceRuntimeConfig.accountId(),
                    occurredAt,
                    receivedAt,
                    metrics,
                    origin,
                    new IngestionContext(null, null, null, Map.of(
                            "httpStatus", Integer.toString(statusCode),
                            "endpointHost", endpoint.getHost() == null ? "" : endpoint.getHost())),
                    false);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("HTTP polling response body must be JSON", exception);
        }
    }

    private Instant timestamp(JsonNode root, Map<String, Object> mapping, Instant fallback) {
        String path = string(mapping.getOrDefault("timestampPath", "timestamp"), "timestampPath");
        JsonNode node = at(root, path);
        if (node == null || node.isMissingNode() || node.isNull()) {
            return fallback;
        }
        if (!node.isTextual()) {
            throw new IllegalArgumentException("HTTP polling timestamp mapping must resolve to an ISO-8601 string");
        }
        return Instant.parse(node.asText());
    }

    private Map<String, Object> metrics(JsonNode root, Map<String, Object> mapping) {
        Object configured = mapping.get("metrics");
        if (configured instanceof Map<?, ?> metricPaths) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : metricPaths.entrySet()) {
                String metricName = string(entry.getKey(), "metric name");
                String path = string(entry.getValue(), "metric path");
                JsonNode node = at(root, path);
                if (node == null || node.isMissingNode() || node.isNull()) {
                    throw new IllegalArgumentException("HTTP polling metric mapping missing path " + path);
                }
                result.put(metricName, scalar(node, metricName));
            }
            return Map.copyOf(result);
        }
        JsonNode metricsNode = root.get("metrics");
        if (metricsNode == null || !metricsNode.isObject()) {
            throw new IllegalArgumentException("HTTP polling mapping must define metrics or response must contain metrics object");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = metricsNode.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            result.put(field.getKey(), scalar(field.getValue(), field.getKey()));
        }
        return Map.copyOf(result);
    }

    private JsonNode at(JsonNode root, String path) {
        JsonNode current = root;
        for (String segment : path.split("\\.")) {
            if (segment.isBlank()) {
                throw new IllegalArgumentException("HTTP polling mapping path contains a blank segment");
            }
            current = current.get(segment);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    private Object scalar(JsonNode node, String name) {
        if (node.isNumber()) {
            return node.numberValue();
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        throw new IllegalArgumentException("HTTP polling metric " + name + " must resolve to a scalar value");
    }

    private String string(Object value, String name) {
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("HTTP polling " + name + " must be a non-blank string");
        }
        return text;
    }
}
