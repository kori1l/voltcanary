package com.voltcanary.aiagent.vehicle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.aiagent.model.AgentAction;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class VehicleMonitoringExecutor {

    private final VehicleMonitoringOwner owner;
    private final ObjectMapper mapper;

    public Map<String, Object> execute(
        AgentAction action,
        String authorization,
        UUID actor,
        boolean inspectOnly
    ) {
        if (
            !action.requiresConfirmation() ||
            !actor.toString().equals(action.parameters().get("userId"))
        ) throw new IllegalArgumentException("Monitoring review owner changed");
        UUID device = UUID.fromString(String.valueOf(action.parameters().get("deviceId")));
        var expected = mapper.valueToTree(action.parameters().get("review"));
        UUID review = UUID.fromString(expected.path("id").asText());
        var actual = owner.read(authorization, actor, device, "/reviews/" + review);
        check(expected, actual);
        if (!inspectOnly && actual.path("status").asText().equals("PREPARED")) {
            if (action.parameters().get("sourceId") != null) {
                String selected = action.parameters().get("sourceId").toString();
                boolean available = false;
                for (var source : owner.sources(authorization, device)) {
                    if (
                        selected.equals(source.path("id").asText()) &&
                        source.path("protocol").asText().equals("REST") &&
                        source.path("enabled").asBoolean(true) &&
                        source.path("status").asText("ACTIVE").equals("ACTIVE")
                    ) available = true;
                }
                if (!available) throw new IllegalArgumentException(
                    "Selected GPS source is no longer available"
                );
            }
            actual = owner.post(
                authorization,
                actor,
                device,
                "/reviews/" + review + "/confirm",
                Map.of(
                    "digest",
                    expected.path("digest").asText(),
                    "idempotencyKey",
                    expected.path("idempotencyKey").asText()
                )
            );
            check(expected, actual);
        }
        if (
            !actual.path("status").asText().equals("SUCCEEDED") ||
            actual.path("appliedRevision").asLong() != expected.path("baseRevision").asLong() + 1
        ) {
            throw new IllegalStateException(
                "Monitoring outcome not committed; inspect the same review"
            );
        }
        String route = "/vehicles?deviceId=" + device + "&section=ZONES&reviewId=" + review;
        if (action.parameters().get("sourceId") != null) route +=
            "&dataSourceId=" + UUID.fromString(action.parameters().get("sourceId").toString());
        return Map.of(
            "deviceId",
            device.toString(),
            "monitoringReviewId",
            review.toString(),
            "revision",
            actual.path("appliedRevision").asLong(),
            "settings",
            mapper.convertValue(actual.path("after"), Map.class),
            "route",
            route
        );
    }

    private static void check(
        com.fasterxml.jackson.databind.JsonNode expected,
        com.fasterxml.jackson.databind.JsonNode actual
    ) {
        for (String key : java.util.List.of(
            "id",
            "deviceId",
            "baseRevision",
            "before",
            "after",
            "digest",
            "idempotencyKey",
            "expiresAt"
        )) {
            if (!expected.path(key).equals(actual.path(key))) throw new IllegalArgumentException(
                "Monitoring owner review changed"
            );
        }
    }
}
