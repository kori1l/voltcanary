package com.voltcanary.telemetry.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.voltcanary.telemetry.config.TelemetryAuthorizationProperties;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

@Component
public class VariableSourceAccess {
    private final RestClient client;
    private final String token;

    public VariableSourceAccess(TelemetryAuthorizationProperties properties, RestClient.Builder builder) {
        var factory = new JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder()
                .connectTimeout(properties.getTimeout()).build());
        factory.setReadTimeout(properties.getTimeout());
        client = builder.baseUrl(properties.getDeviceServiceBaseUrl()).requestFactory(factory).build();
        token = properties.getInternalServiceToken();
    }

    /** Device authorization must precede this association check. */
    public void require(UUID device, UUID source) {
        try {
            JsonNode result = client.get().uri("/internal/data-sources/{id}", source)
                    .header("X-Internal-Service-Token", token).retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (request, response) -> {
                        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Variable source access denied");
                    }).body(JsonNode.class);
            if (result == null || !device.toString().equals(result.path("deviceId").asText())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Variable source access denied");
            }
        } catch (ResponseStatusException failure) { throw failure; }
        catch (RuntimeException failure) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Variable source authorization unavailable");
        }
    }

    public boolean active(UUID device, UUID source, UUID variable) {
        try {
            JsonNode result = client.get().uri(uri -> uri.path("/internal/device-variable-runtime-access")
                    .queryParam("deviceId", device).queryParam("sourceId", source).queryParam("variableId", variable).build())
                    .header("X-Internal-Service-Token", token).retrieve().body(JsonNode.class);
            if (result == null || !result.has("enabled")) throw new IllegalStateException("Missing capability response");
            return result.path("enabled").asBoolean();
        } catch (RuntimeException failure) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Current variable capabilities unavailable");
        }
    }

    public java.util.Set<String> activeBatch(UUID device, java.util.List<com.voltcanary.telemetry.variable.VariableSeriesQueries.Descriptor> descriptors) {
        if (descriptors.isEmpty()) return java.util.Set.of();
        if (descriptors.size() > 500) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Variable catalogue exceeds batch budget");
        var targets = descriptors.stream().map(item -> java.util.Map.of("dataSourceId", item.dataSourceId(), "variableId", item.variableId())).toList();
        try {
            JsonNode response = client.post().uri("/internal/device-variable-runtime-access")
                    .header("X-Internal-Service-Token", token).body(java.util.Map.of("deviceId", device, "targets", targets))
                    .retrieve().body(JsonNode.class);
            if (response == null || !response.isArray()) throw new IllegalStateException("Missing capabilities");
            var active = new java.util.HashSet<String>();
            response.forEach(item -> active.add(item.path("dataSourceId").asText() + ":" + item.path("variableId").asText()));
            return java.util.Set.copyOf(active);
        } catch (RuntimeException failure) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Current variable capabilities unavailable");
        }
    }
}
