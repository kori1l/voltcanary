package com.voltcanary.device.workspace;

import com.voltcanary.common.security.WorkspaceAuthorization.Membership;
import com.voltcanary.device.config.InternalServiceProperties;
import com.voltcanary.device.config.WorkspaceIdentityProperties;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

@Component
public class CurrentWorkspaceMembership {

    private final RestClient client;
    private final String token;

    public CurrentWorkspaceMembership(
        RestClient.Builder builder,
        WorkspaceIdentityProperties properties,
        InternalServiceProperties internal
    ) {
        client = builder.baseUrl(properties.getBaseUrl()).build();
        token = internal.getServiceToken();
    }

    public Membership require(UUID workspace, UUID user) {
        try {
            Membership result = client
                .get()
                .uri("/internal/workspaces/{workspace}/membership?userId={user}", workspace, user)
                .header("X-Internal-Service-Token", token)
                .retrieve()
                .body(Membership.class);
            if (
                result == null ||
                !workspace.equals(result.workspaceId()) ||
                !user.equals(result.userId())
            ) throw new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "Active workspace membership required"
            );
            return result;
        } catch (org.springframework.web.client.HttpClientErrorException denied) {
            throw new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "Active workspace membership required"
            );
        } catch (ResponseStatusException denied) {
            throw denied;
        } catch (Exception unavailable) {
            throw new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Membership authority unavailable"
            );
        }
    }

    public Membership manager(UUID workspace, UUID user) {
        Membership member = require(workspace, user);
        if (
            !java.util.List.of("OWNER", "MANAGER").contains(member.role())
        ) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Workspace manager required");
        return member;
    }

    public boolean teamExists(UUID workspace, UUID team) {
        try {
            var result = client
                .get()
                .uri("/internal/workspaces/{workspace}/teams/{team}", workspace, team)
                .header("X-Internal-Service-Token", token)
                .retrieve()
                .body(com.fasterxml.jackson.databind.JsonNode.class);
            return result != null && result.path("exists").asBoolean(false);
        } catch (Exception unavailable) {
            throw new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Team authority unavailable"
            );
        }
    }
}
