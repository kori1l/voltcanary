package com.voltcanary.simulator.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.voltcanary.simulator.config.SimulationTargetProperties;
import com.voltcanary.simulator.yaml.SimulationConfigModel;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

@Component
public class VehicleSimulationTarget {
    private final RestClient client;
    private final String token;

    public VehicleSimulationTarget(SimulationTargetProperties properties, RestClient.Builder builder) {
        client = builder.baseUrl(properties.getDeviceServiceBaseUrl()).build();
        token = properties.getInternalServiceToken();
    }

    public void require(UUID userId, UUID deviceId, UUID dataSourceId, SimulationConfigModel model) {
        if (!"VEHICLE_GPS".equals(model.profile())) {
            return;
        }
        try {
            JsonNode access = client.get().uri("/internal/devices/{id}/access?userId={user}", deviceId, userId)
                    .header("X-Internal-Service-Token", token).retrieve().body(JsonNode.class);
            if (access == null || !access.path("allowed").asBoolean()) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Vehicle access denied");
            }
            JsonNode source = client.get().uri(builder -> builder.path("/internal/devices/{id}/position-source")
                    .queryParam("sourceId", model.sourceId()).build(deviceId))
                    .header("X-Internal-Service-Token", token).retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (request, response) -> {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Vehicle source unavailable");
                    }).body(JsonNode.class);
            if (source == null || !"VEHICLE".equals(source.path("deviceType").asText())
                    || !"SIMULATOR".equals(source.path("sourceType").asText())
                    || !"REST".equals(source.path("protocol").asText())
                    || !dataSourceId.toString().equals(source.path("dataSourceId").asText())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "VEHICLE_GPS requires a vehicle SIMULATOR/REST source");
            }
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Vehicle source authorization unavailable");
        }
    }
}
