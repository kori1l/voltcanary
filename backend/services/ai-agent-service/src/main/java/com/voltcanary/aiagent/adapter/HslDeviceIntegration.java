package com.voltcanary.aiagent.adapter;

import com.voltcanary.aiagent.config.AiAgentProperties;
import com.voltcanary.aiagent.port.DeviceIntegration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class HslDeviceIntegration implements DeviceIntegration {

    private final RestClient connector;

    public HslDeviceIntegration(RestClient.Builder builder, AiAgentProperties properties) {
        connector = builder.baseUrl(properties.getServices().getHslConnectorBaseUrl()).build();
    }

    @Override
    public String provider() {
        return "HSL_HFP";
    }

    private Map<String, Object> request(Map<String, Object> intent, String key) {
        if (
            !List.of("bus", "tram").contains(intent.get("mode")) ||
            !String.valueOf(intent.get("vehicleKey")).matches("[0-9]{4}-[0-9]{5}")
        ) {
            throw new IllegalArgumentException("Invalid HSL identity");
        }
        return Map.of(
            "mode",
            intent.get("mode"),
            "vehicleKey",
            intent.get("vehicleKey"),
            "creationKey",
            key
        );
    }

    @Override
    public void prepare(Map<String, Object> intent, String authorization, String key) {
        connector
            .post()
            .uri("/availability")
            .header("Authorization", authorization)
            .body(request(intent, key))
            .retrieve()
            .toBodilessEntity();
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> connect(
        String deviceId,
        Map<String, Object> intent,
        String authorization,
        String key
    ) {
        var body = new LinkedHashMap<>(request(intent, key));
        body.put("deviceId", deviceId);
        return connector
            .post()
            .uri("/bindings")
            .header("Authorization", authorization)
            .body(body)
            .retrieve()
            .body(Map.class);
    }

    @Override
    public Map<?, ?> status(String deviceId, String authorization) {
        return connector
            .get()
            .uri("/bindings/{id}", deviceId)
            .header("Authorization", authorization)
            .retrieve()
            .body(Map.class);
    }
}
