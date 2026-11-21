package com.voltcanary.aiagent.vehicle;

import com.fasterxml.jackson.databind.JsonNode;
import com.voltcanary.aiagent.config.AiAgentProperties;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class VehicleMonitoringOwner {
    private final RestClient telemetry;
    private final RestClient devices;
    public VehicleMonitoringOwner(RestClient.Builder builder, AiAgentProperties properties) {
        telemetry = builder.clone().baseUrl(properties.getServices().getTelemetryBaseUrl()).build();
        devices = builder.clone().baseUrl(properties.getServices().getDeviceBaseUrl()).build();
    }
    public JsonNode device(String authorization, UUID id) { return devices.get().uri("/devices/{id}", id).header("Authorization", authorization).retrieve().body(JsonNode.class); }
    public JsonNode sources(String authorization, UUID id) { return devices.get().uri("/devices/{id}/data-sources", id).header("Authorization", authorization).retrieve().body(JsonNode.class); }
    public JsonNode read(String authorization, UUID user, UUID device, String suffix) {
        return telemetry.get().uri(path(device) + suffix).header("Authorization", authorization).header("X-User-Id", user.toString()).retrieve().body(JsonNode.class);
    }
    public JsonNode post(String authorization, UUID user, UUID device, String suffix, Object body) {
        return telemetry.post().uri(path(device) + suffix).header("Authorization", authorization).header("X-User-Id", user.toString()).body(body).retrieve().body(JsonNode.class);
    }
    private static String path(UUID device) { return "/telemetry/devices/" + device + "/vehicle-monitoring"; }
}
