package com.voltcanary.aiagent.service;

import com.voltcanary.aiagent.config.AiAgentProperties;
import com.voltcanary.aiagent.model.AgentAction;
import com.voltcanary.aiagent.port.DeviceIntegration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Owns device setup only; dashboard preparation/execution never enters this workflow. */
@Component
public class DeviceSetupExecutor {
    private final RestClient devices;
    private final List<DeviceIntegration> integrations;
    private final AiAgentProperties properties;

    public DeviceSetupExecutor(RestClient.Builder builder, AiAgentProperties properties, List<DeviceIntegration> integrations) {
        this.properties = properties;
        devices = builder.baseUrl(properties.getServices().getDeviceBaseUrl()).build();
        this.integrations = List.copyOf(integrations);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> execute(AgentAction action, String authorization, String key) {
        var body = new LinkedHashMap<String, Object>();
        for (String field : List.of("name", "type", "locationId", "manufacturer", "model")) {
            if (action.parameters().get(field) != null) body.put(field, action.parameters().get(field));
        }
        Map<String, Object> connection = action.parameters().get("connection") instanceof Map<?, ?> value
                ? (Map<String, Object>) value : Map.of();
        if (!connection.isEmpty()) {
            requireEnabled();
            if (!"HSL_HFP".equals(connection.get("provider")) || !"VEHICLE".equals(body.get("type"))
                    || !List.of("bus", "tram").contains(connection.get("mode"))
                    || !String.valueOf(connection.get("vehicleKey")).matches("[0-9]{4}-[0-9]{5}")) {
                throw new IllegalArgumentException("Unsupported reviewed integration");
            }
            // Fixed destination, no source mutation. Capacity/conflict validation precedes creation.
            integration(connection.get("provider").toString()).prepare(connection, authorization, key);
            body.put("metadata", Map.of("setupMode", "real", "feedProvider", "HSL", "transportMode", connection.get("mode"),
                    "externalVehicleId", connection.get("vehicleKey"), "city", "Helsinki", "attribution", "HSL HFP open data"));
        }
        Map<String, Object> device = devices.post().uri("/devices").header("Authorization", authorization)
                .header("Idempotency-Key", key).body(body).retrieve().body(Map.class);
        if (device == null || device.get("id") == null) throw new IllegalStateException("Missing created device identity");
        var result = new LinkedHashMap<String, Object>();
        result.put("deviceId", device.get("id"));
        result.put("device", device);
        result.put("route", "/devices/" + device.get("id") + "?tab=configuration");
        result.put("setupStatus", "SOURCE_NOT_CONNECTED");
        if (!connection.isEmpty()) {
            Map<String, Object> setup = integration(connection.get("provider").toString())
                    .connect(device.get("id").toString(), connection, authorization, key);
            if (setup == null || !device.get("id").equals(setup.get("deviceId"))) throw new IllegalStateException("Invalid setup receipt");
            result.putAll(setup);
            result.put("route", "/devices/" + device.get("id") + "?tab=movement");
        }
        return result;
    }

    public Map<?, ?> status(UUID deviceId, String authorization) {
        requireEnabled();
        return integration("HSL_HFP").status(deviceId.toString(), authorization);
    }

    /** Separate source step for new v2 plans; legacy composite device commands retain execute(). */
    public Map<String, Object> connectExisting(AgentAction action, String authorization, String key) {
        requireEnabled();
        var values = action.parameters();
        if (!"HSL_HFP".equals(values.get("provider"))) throw new IllegalArgumentException("Integration is not installed");
        String deviceId = UUID.fromString(String.valueOf(values.get("deviceId"))).toString();
        var intent = Map.<String, Object>of("provider", "HSL_HFP", "mode", values.get("mode"), "vehicleKey", values.get("vehicleKey"));
        var adapter = integration("HSL_HFP");
        adapter.prepare(intent, authorization, key);
        var receipt = adapter.connect(deviceId, intent, authorization, key);
        if (receipt == null || !deviceId.equals(String.valueOf(receipt.get("deviceId"))) || receipt.get("dataSourceId") == null)
            throw new IllegalStateException("Invalid source receipt");
        var result = new LinkedHashMap<>(receipt);
        result.put("route", "/devices/" + deviceId + "?tab=movement");
        return result;
    }

    private DeviceIntegration integration(String provider) {
        return integrations.stream().filter(value -> value.provider().equals(provider)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Integration is not installed"));
    }

    private void requireEnabled() {
        if (!properties.isDeviceSetupEnabled()) throw new IllegalArgumentException("Device integration setup is disabled");
    }
}
