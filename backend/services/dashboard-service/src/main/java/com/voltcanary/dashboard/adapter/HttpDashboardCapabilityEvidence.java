package com.voltcanary.dashboard.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.voltcanary.dashboard.config.DashboardReadProperties;
import com.voltcanary.dashboard.domain.DashboardFailure;
import com.voltcanary.dashboard.port.DashboardCapabilityEvidence;
import com.voltcanary.dashboard.port.TemplateDeviceCatalogue;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
public class HttpDashboardCapabilityEvidence implements DashboardCapabilityEvidence {

    private final TemplateDeviceCatalogue catalogue;
    private final RestClient telemetry;

    public HttpDashboardCapabilityEvidence(
        TemplateDeviceCatalogue catalogue,
        DashboardReadProperties properties
    ) {
        this.catalogue = catalogue;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.timeout());
        factory.setReadTimeout(properties.timeout());
        telemetry = com.voltcanary.common.web.JacksonRestClients.builder()
            .baseUrl(properties.telemetryBaseUrl().toString())
            .requestFactory(factory)
            .build();
    }

    @Override
    public Device read(String authorization, UUID deviceId) {
        var device = catalogue.read(authorization, deviceId);
        JsonNode response = get("/telemetry/devices/" + deviceId + "/fields", authorization);
        if (response == null || !response.isArray()) throw new DashboardFailure(
            "INVALID_READ_RESPONSE",
            "Recorded field metadata is unavailable"
        );
        if (response.size() > 400) {
            throw new DashboardFailure(
                "LIMIT_EXCEEDED",
                "Narrow the device/source selection; recorded field metadata exceeds limits"
            );
        }
        var fields = new ArrayList<Field>();
        for (JsonNode field : response) {
            if (
                !field.path("key").asText().matches("[A-Za-z][A-Za-z0-9_.]{0,99}") ||
                !List.of("number", "boolean", "string").contains(field.path("type").asText())
            ) {
                throw new DashboardFailure(
                    "INVALID_READ_RESPONSE",
                    "Recorded field metadata is invalid"
                );
            }
            try {
                fields.add(
                    new Field(
                        field.path("key").asText(),
                        field.path("label").asText(),
                        field.path("type").asText(),
                        field.path("unit").asText(),
                        field.path("sourceId").asText(),
                        field.hasNonNull("value"),
                        field.hasNonNull("recordedAt")
                            ? Instant.parse(field.path("recordedAt").asText())
                            : null
                    )
                );
            } catch (java.time.format.DateTimeParseException invalid) {
                throw new DashboardFailure(
                    "INVALID_READ_RESPONSE",
                    "Recorded field timestamp is invalid"
                );
            }
        }
        var sources = new ArrayList<Source>();
        for (var source : device.sources()) {
            if (
                source.sourceId() == null || source.sourceId().isBlank()
            ) throw new DashboardFailure(
                "INVALID_READ_RESPONSE",
                "Recorded source identity is unavailable"
            );
            String freshness = "UNKNOWN";
            if (device.type().equals("VEHICLE")) {
                JsonNode latest = get(
                    "/telemetry/devices/" + deviceId + "/positions/latest?sourceId={source}",
                    authorization,
                    source.sourceId()
                );
                if (latest == null) throw new DashboardFailure(
                    "INVALID_READ_RESPONSE",
                    "Source availability response is unavailable"
                );
                freshness = latest.path("freshness").asText("UNKNOWN");
                if (!List.of("FRESH", "STALE", "NO_DATA", "UNKNOWN").contains(freshness)) {
                    throw new DashboardFailure(
                        "INVALID_READ_RESPONSE",
                        "Source availability response is invalid"
                    );
                }
            }
            sources.add(new Source(source.id(), source.sourceId(), freshness));
        }
        return new Device(
            device.id(),
            device.name(),
            device.type(),
            device.metrics(),
            List.copyOf(sources),
            List.copyOf(fields)
        );
    }

    private JsonNode get(String path, String authorization, Object... variables) {
        try {
            return telemetry
                .get()
                .uri(path, variables)
                .header("Authorization", authorization)
                .retrieve()
                .body(JsonNode.class);
        } catch (RestClientResponseException failure) {
            if (failure.getStatusCode().value() == 404 || failure.getStatusCode().value() == 403) {
                throw new DashboardFailure(
                    "NOT_FOUND",
                    "Selected capability evidence is unavailable"
                );
            }
            throw new DashboardFailure(
                "DEPENDENCY_UNAVAILABLE",
                "Capability evidence could not be read"
            );
        } catch (RestClientException failure) {
            throw new DashboardFailure(
                "DEPENDENCY_UNAVAILABLE",
                "Capability evidence could not be read"
            );
        }
    }
}
