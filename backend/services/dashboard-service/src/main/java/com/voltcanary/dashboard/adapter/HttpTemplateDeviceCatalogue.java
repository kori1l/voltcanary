package com.voltcanary.dashboard.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.voltcanary.dashboard.config.DashboardReadProperties;
import com.voltcanary.dashboard.domain.DashboardFailure;
import com.voltcanary.dashboard.port.TemplateDeviceCatalogue;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
public class HttpTemplateDeviceCatalogue implements TemplateDeviceCatalogue {

    private final RestClient devices;
    private final RestClient telemetry;

    public HttpTemplateDeviceCatalogue(DashboardReadProperties properties) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.timeout());
        factory.setReadTimeout(properties.timeout());
        devices = com.voltcanary.common.web.JacksonRestClients.builder()
            .baseUrl(properties.deviceBaseUrl().toString())
            .requestFactory(factory)
            .build();
        telemetry = com.voltcanary.common.web.JacksonRestClients.builder()
            .baseUrl(properties.telemetryBaseUrl().toString())
            .requestFactory(factory)
            .build();
    }

    @Override
    public Device read(String authorization, UUID id) {
        JsonNode device = get(devices, "/devices/" + id, authorization);
        JsonNode metrics = get(telemetry, "/telemetry/devices/" + id + "/metrics", authorization);
        JsonNode sources = get(devices, "/devices/" + id + "/data-sources", authorization);
        if (
            !device.path("id").asText().equals(id.toString()) ||
            !metrics.isArray() ||
            !sources.isArray() ||
            metrics.size() > 100 ||
            sources.size() > 100
        ) {
            throw new DashboardFailure(
                "INVALID_READ_RESPONSE",
                "Template capability catalogue is invalid or exceeds limits"
            );
        }
        List<Metric> supported = new ArrayList<>();
        for (JsonNode metric : metrics)
            supported.add(new Metric(metric.path("name").asText(), metric.path("unit").asText()));
        List<Source> active = new ArrayList<>();
        for (JsonNode source : sources) {
            if (
                !source.path("enabled").asBoolean(true) ||
                !"ACTIVE".equals(source.path("status").asText("ACTIVE"))
            ) continue;
            active.add(
                new Source(
                    UUID.fromString(source.path("id").asText()),
                    source.path("externalId").asText()
                )
            );
        }
        return new Device(
            id,
            device.path("name").asText(),
            device.path("type").asText(),
            List.copyOf(supported),
            List.copyOf(active)
        );
    }

    private JsonNode get(RestClient client, String path, String authorization) {
        try {
            return client
                .get()
                .uri(path)
                .header("Authorization", authorization)
                .retrieve()
                .body(JsonNode.class);
        } catch (RestClientResponseException failure) {
            if (failure.getStatusCode().is4xxClientError()) throw new DashboardFailure(
                "NO_ACCESS",
                "Template binding is unavailable"
            );
            throw new DashboardFailure("DEPENDENCY_UNAVAILABLE", "Template catalogue unavailable");
        } catch (RestClientException failure) {
            throw new DashboardFailure("DEPENDENCY_UNAVAILABLE", "Template catalogue unavailable");
        }
    }
}
