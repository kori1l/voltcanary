package com.voltcanary.aiagent.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.voltcanary.aiagent.port.PrimaryDeviceReads;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.client.RestClient;

@RequiredArgsConstructor
public final class PrimaryDeviceHttpReads implements PrimaryDeviceReads {

    private final RestClient client;

    @Override
    public JsonNode device(UUID deviceId, String authorization) {
        return PlanningContextRead.read(client, "/devices/" + deviceId, authorization, true);
    }

    @Override
    public JsonNode source(UUID deviceId, UUID sourceId, String authorization) {
        return PlanningContextRead.read(
            client,
            "/devices/" + deviceId + "/data-sources/" + sourceId,
            authorization,
            true
        );
    }
}
