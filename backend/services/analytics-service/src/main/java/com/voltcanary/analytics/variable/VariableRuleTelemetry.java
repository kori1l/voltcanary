package com.voltcanary.analytics.variable;

import com.fasterxml.jackson.databind.JsonNode;
import com.voltcanary.analytics.config.AnalyticsDownstreamProperties;
import com.voltcanary.analytics.config.AnalyticsInternalProperties;
import com.voltcanary.common.events.telemetry.VariableRuleSelector;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class VariableRuleTelemetry {

    public record Sample(Instant timestamp, double value, int revision) {}

    private final RestClient telemetry;
    private final String token;

    public VariableRuleTelemetry(
        RestClient.Builder builder,
        AnalyticsDownstreamProperties properties,
        AnalyticsInternalProperties internal
    ) {
        var factory = new JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder()
                .connectTimeout(properties.getTelemetryService().getTimeout())
                .build()
        );
        factory.setReadTimeout(properties.getTelemetryService().getTimeout());
        telemetry = builder
            .baseUrl(properties.getTelemetryService().getBaseUrl())
            .requestFactory(factory)
            .build();
        token = internal.getServiceToken();
    }

    /** Fresh metadata, without permission-stale cache entries. */
    public boolean active(UUID device, VariableRuleSelector selector) {
        JsonNode descriptors = telemetry
            .get()
            .uri("/internal/telemetry/devices/{id}/variables", device)
            .header("X-Internal-Service-Token", token)
            .retrieve()
            .body(JsonNode.class);
        if (descriptors == null || !descriptors.isArray()) throw new IllegalStateException(
            "Variable metadata unavailable"
        );
        for (JsonNode descriptor : descriptors) {
            if (
                selector.variableId().toString().equals(descriptor.path("variableId").asText()) &&
                selector.dataSourceId().toString().equals(descriptor.path("dataSourceId").asText())
            ) {
                return (
                    descriptor.path("enabled").asBoolean() &&
                    selector.unit().equals(descriptor.path("unit").asText())
                );
            }
        }
        return false;
    }

    public List<Sample> samples(
        UUID device,
        VariableRuleSelector selector,
        Instant from,
        Instant to
    ) {
        List<Sample> samples = new ArrayList<>();
        Instant cursor = null;
        for (int page = 0; page < 10; page++) {
            Instant next = cursor;
            JsonNode response = telemetry
                .get()
                .uri(uri -> {
                    uri.path("/internal/telemetry/devices/{id}/readings")
                        .queryParam("variableId", selector.variableId())
                        .queryParam("sourceId", selector.dataSourceId())
                        .queryParam("from", from)
                        .queryParam("to", to)
                        .queryParam("limit", 2000);
                    if (next != null) uri.queryParam("cursor", next);
                    return uri.build(device);
                })
                .header("X-Internal-Service-Token", token)
                .retrieve()
                .body(JsonNode.class);
            if (response == null || !selector.unit().equals(response.path("unit").asText())) {
                throw new IllegalStateException(
                    "Variable measurements unavailable or unit mismatch"
                );
            }
            for (JsonNode point : response.path("points")) {
                samples.add(
                    new Sample(
                        Instant.parse(point.path("timestamp").asText()),
                        point.path("value").asDouble(),
                        point.path("revision").asInt()
                    )
                );
            }
            if (!response.path("hasMore").asBoolean()) return samples;
            Instant previous = cursor;
            cursor = Instant.parse(response.path("nextCursor").asText());
            if (previous != null && !previous.isBefore(cursor)) throw new IllegalStateException(
                "Nonadvancing measurement cursor"
            );
        }
        throw new IllegalArgumentException("Dry run exceeds 20000 observations; narrow the period");
    }
}
