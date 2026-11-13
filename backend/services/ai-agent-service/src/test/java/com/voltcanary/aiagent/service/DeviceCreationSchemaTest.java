package com.voltcanary.aiagent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class DeviceCreationSchemaTest {
    @Test void admitsEveryCurrentDeviceTypeAndLocationWithoutRequiringOptionalUserFacts() {
        var context = Map.<String, Object>of("deviceTypes", List.of(
                Map.of("ref", "vehicle-ref", "code", "VEHICLE"), Map.of("ref", "fridge-ref", "code", "FRIDGE"),
                Map.of("ref", "sensor-ref", "code", "SENSOR"), Map.of("ref", "custom-ref", "code", "CUSTOM_TYPE")),
                "locations", List.of(Map.of("ref", "room-ref", "name", "Kitchen")));
        JsonNode root = new ObjectMapper().valueToTree(DeviceCreationSchema.response(context));
        var fields = root.path("properties").path("actions").path("items").path("properties").path("parameters").path("properties");
        assertThat(fields.path("type").path("anyOf").get(0).path("enum").toString()).contains("VEHICLE", "FRIDGE", "SENSOR", "CUSTOM_TYPE");
        assertThat(fields.path("deviceTypeRef").toString()).contains("vehicle-ref", "fridge-ref", "sensor-ref", "custom-ref");
        assertThat(fields.path("locationRef").toString()).contains("room-ref");
        for (String optional : List.of("manufacturer", "model", "locationRef")) {
            assertThat(fields.path(optional).path("anyOf").get(1).path("type").asText()).isEqualTo("null");
        }
        assertThat(DeviceCreationPrompt.SYSTEM).contains("OPTIONAL", "other integrations", "latest answer");
    }
}
