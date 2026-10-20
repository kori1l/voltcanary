package com.voltcanary.telemetry.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.voltcanary.telemetry.config.TelemetryAuthorizationProperties;
import java.util.UUID;
import java.util.List;
import com.voltcanary.common.web.VehiclePositionTargets;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

@Component
public class PositionSourceAccess {
    private final RestClient client;
    private final String token;
    public PositionSourceAccess(TelemetryAuthorizationProperties properties, RestClient.Builder builder) {
        var factory = new JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder().connectTimeout(properties.getTimeout()).build());
        factory.setReadTimeout(properties.getTimeout());
        client = builder.baseUrl(properties.getDeviceServiceBaseUrl()).requestFactory(factory).build();
        token = properties.getInternalServiceToken();
    }

    public void requireFleet(String user, List<VehiclePositionTargets.Target> targets) {
        if (user == null || user.isBlank()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing authenticated user");
        final UUID userId;
        try { userId = UUID.fromString(user); }
        catch (IllegalArgumentException invalid) { throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid authenticated user"); }
        try {
            JsonNode response = client.post().uri("/internal/vehicles/position-sources")
                    .header("X-Internal-Service-Token", token).body(new VehiclePositionTargets(userId, targets))
                    .retrieve().onStatus(HttpStatusCode::is4xxClientError, (request, result) -> {
                        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Fleet binding access denied");
                    }).body(JsonNode.class);
            if (response == null || !response.path("allowed").asBoolean()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Fleet binding access denied");
        } catch (ResponseStatusException exception) { throw exception; }
        catch (Exception exception) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Fleet authorization unavailable"); }
    }

    /** Call only after the public or signed dashboard device authorization. */
    public void require(UUID device, String source) {
        if (source == null || source.isBlank() || source.length() > 255) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sourceId is required");
        }
        try {
            JsonNode response = client.get().uri(builder -> builder.path("/internal/devices/{id}/position-source")
                    .queryParam("sourceId", source).build(device)).header("X-Internal-Service-Token", token)
                    .retrieve().onStatus(HttpStatusCode::is4xxClientError, (request, result) -> {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Source does not belong to device");
                    }).body(JsonNode.class);
            if (response == null || !device.toString().equals(response.path("deviceId").asText())
                    || !source.equals(response.path("sourceId").asText())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Source does not belong to device");
            }
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Source authorization unavailable");
        }
    }
}
