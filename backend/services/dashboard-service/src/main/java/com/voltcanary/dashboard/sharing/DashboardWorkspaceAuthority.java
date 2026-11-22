package com.voltcanary.dashboard.sharing;

import com.voltcanary.common.security.WorkspaceAuthorization.Decision;
import com.voltcanary.common.security.WorkspaceAuthorization.Membership;
import com.voltcanary.dashboard.config.DashboardReadProperties;
import com.voltcanary.dashboard.domain.DashboardFailure;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class DashboardWorkspaceAuthority {

    private final RestClient client;
    private final String token;

    public DashboardWorkspaceAuthority(
        RestClient.Builder builder,
        DashboardReadProperties properties
    ) {
        client = builder.baseUrl(properties.deviceBaseUrl().toString()).build();
        token = properties.internalServiceToken();
    }

    public Membership member(UUID workspace, UUID user) {
        try {
            Membership result = client
                .get()
                .uri("/internal/workspaces/{w}/membership?userId={u}", workspace, user)
                .header("X-Internal-Service-Token", token)
                .retrieve()
                .body(Membership.class);
            if (
                result == null ||
                !workspace.equals(result.workspaceId()) ||
                !user.equals(result.userId())
            ) throw new DashboardFailure("NO_ACCESS", "Current workspace membership unavailable");
            return result;
        } catch (org.springframework.web.client.HttpClientErrorException denied) {
            throw new DashboardFailure("NO_ACCESS", "Current workspace membership unavailable");
        } catch (DashboardFailure denied) {
            throw denied;
        } catch (Exception unavailable) {
            throw new DashboardFailure("DEPENDENCY_UNAVAILABLE", "Workspace authority unavailable");
        }
    }

    public boolean team(UUID workspace, UUID team) {
        try {
            var result = client
                .get()
                .uri("/internal/workspaces/{w}/teams/{t}", workspace, team)
                .header("X-Internal-Service-Token", token)
                .retrieve()
                .body(com.fasterxml.jackson.databind.JsonNode.class);
            return result != null && result.path("exists").asBoolean(false);
        } catch (Exception unavailable) {
            throw new DashboardFailure(
                "DEPENDENCY_UNAVAILABLE",
                "Workspace team authority unavailable"
            );
        }
    }

    public Decision resources(UUID workspace, UUID account, UUID user, String action) {
        try {
            Decision result = client
                .get()
                .uri(
                    "/internal/workspaces/{w}/resource-access?accountId={a}&userId={u}&action={action}",
                    workspace,
                    account,
                    user,
                    action
                )
                .header("X-Internal-Service-Token", token)
                .retrieve()
                .body(Decision.class);
            if (
                result == null ||
                !workspace.equals(result.workspaceId()) ||
                !account.equals(result.accountId()) ||
                !user.equals(result.userId()) ||
                !action.equals(result.action())
            ) throw new DashboardFailure("NO_ACCESS", "Current resource capability unavailable");
            return result;
        } catch (org.springframework.web.client.HttpClientErrorException denied) {
            throw new DashboardFailure("NO_ACCESS", "Current resource capability unavailable");
        } catch (DashboardFailure denied) {
            throw denied;
        } catch (Exception unavailable) {
            throw new DashboardFailure("DEPENDENCY_UNAVAILABLE", "Resource authority unavailable");
        }
    }
}
