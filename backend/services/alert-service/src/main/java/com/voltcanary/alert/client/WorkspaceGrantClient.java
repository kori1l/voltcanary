package com.voltcanary.alert.client;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

/** Service-authenticated recheck binds a signed caller to an explicit resource action. */
public final class WorkspaceGrantClient {

    private final RestClient client;
    private final String serviceToken;

    public WorkspaceGrantClient(RestClient client, String serviceToken) {
        this.client = client;
        this.serviceToken = serviceToken;
    }

    public Set<UUID> require(
        String authorization,
        UUID workspace,
        UUID account,
        UUID user,
        String action,
        Set<UUID> devices
    ) {
        if (
            authorization == null ||
            !authorization.startsWith("Bearer ") ||
            workspace == null ||
            account == null ||
            user == null ||
            devices.size() > 500
        ) throw new ResponseStatusException(
            HttpStatus.FORBIDDEN,
            "Explicit signed workspace action required"
        );
        try {
            var body = Map.of(
                "mode",
                devices.isEmpty() ? "UNBOUND" : "FIXED",
                "workspaceId",
                workspace,
                "accountId",
                account,
                "action",
                action,
                "deviceIds",
                devices
            );
            JsonNode response = client
                .post()
                .uri("/internal/dashboard/scope")
                .header("Authorization", authorization)
                .header("X-Internal-Service-Token", serviceToken)
                .body(body)
                .retrieve()
                .body(JsonNode.class);
            if (
                response == null ||
                !response.path("userId").asText().equals(user.toString()) ||
                !response.path("accountId").asText().equals(account.toString()) ||
                !response.path("devices").isArray()
            ) throw new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "Current workspace actor mismatch"
            );
            Set<UUID> permitted = new java.util.HashSet<>();
            response
                .path("devices")
                .forEach(device -> permitted.add(UUID.fromString(device.path("id").asText())));
            if (!devices.containsAll(permitted)) throw new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "Unexpected workspace resource subset"
            );
            return Set.copyOf(permitted);
        } catch (org.springframework.web.client.HttpClientErrorException denied) {
            throw new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "Current workspace capability unavailable"
            );
        } catch (ResponseStatusException denied) {
            throw denied;
        } catch (Exception unavailable) {
            throw new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Resource authority unavailable"
            );
        }
    }
}
