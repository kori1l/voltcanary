package com.voltcanary.alert.service;

import com.voltcanary.alert.config.AlertAuthorizationProperties;
import com.voltcanary.alert.config.AlertInternalProperties;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

@Service
@Slf4j
public class AlertAccessService {

    private final AlertAuthorizationProperties properties;
    private final RestClient restClient;
    private final String internalServiceToken;

    public AlertAccessService(
            AlertAuthorizationProperties properties,
            RestClient.Builder restClientBuilder,
            AlertInternalProperties internalProperties) {
        this.properties = properties;
        this.internalServiceToken = internalProperties.getServiceToken();
        this.restClient = restClientBuilder
                .baseUrl(properties.getDeviceServiceBaseUrl())
                .build();
    }

    public boolean canReadDevice(UUID deviceId, String userId, String role, String correlationId) {
        if (!properties.isEnabled()) {
            return true;
        }
        if (userId == null || userId.isBlank()) {
            return false;
        }
        try {
            DeviceAccessResponse response = restClient.get()
                    .uri("/internal/devices/{deviceId}/access?userId={userId}", deviceId, userId)
                    .header("X-Correlation-Id", correlationId == null ? "" : correlationId)
                    .header("X-Internal-Service-Token", internalServiceToken)
                    .retrieve()
                    .body(DeviceAccessResponse.class);
            return response != null && response.allowed();
        } catch (Exception exception) {
            log.warn(
                    "alertAccessCheckFailed deviceId={} userId={} reason={}",
                    deviceId,
                    userId,
                    exception.getClass().getSimpleName());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Device authorization unavailable");
        }
    }

    public UUID resolveDeviceAccountId(UUID deviceId, String correlationId) {
        if (!properties.isEnabled()) {
            return null;
        }
        try {
            DeviceOwnerResponse response = restClient.get()
                    .uri("/internal/devices/{deviceId}/owner", deviceId)
                    .header("X-Correlation-Id", correlationId == null ? "" : correlationId)
                    .header("X-Internal-Service-Token", internalServiceToken)
                    .retrieve()
                    .body(DeviceOwnerResponse.class);
            if (response == null || response.accountId() == null) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Device owner snapshot unavailable");
            }
            return response.accountId();
        } catch (Exception exception) {
            log.warn(
                    "alertDeviceOwnerLookupFailed deviceId={} reason={}",
                    deviceId,
                    exception.getClass().getSimpleName());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Device owner lookup unavailable");
        }
    }

    public DeviceRuleContext resolveDeviceRuleContext(UUID deviceId, String correlationId) {
        if (!properties.isEnabled()) {
            return new DeviceRuleContext(deviceId, null, null, null, null);
        }
        try {
            return restClient.get()
                    .uri("/internal/devices/{deviceId}/metadata", deviceId)
                    .header("X-Correlation-Id", correlationId == null ? "" : correlationId)
                    .header("X-Internal-Service-Token", internalServiceToken)
                    .retrieve()
                    .body(DeviceRuleContext.class);
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Device not found");
            }
            log.warn(
                    "alertDeviceRuleContextLookupFailed deviceId={} status={} reason={}",
                    deviceId,
                    exception.getStatusCode().value(),
                    exception.getClass().getSimpleName());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Device metadata unavailable");
        } catch (Exception exception) {
            log.warn(
                    "alertDeviceRuleContextLookupFailed deviceId={} reason={}",
                    deviceId,
                    exception.getClass().getSimpleName());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Device metadata unavailable");
        }
    }

    public void requireReadAccess(UUID deviceId, String userId, String role, String correlationId) {
        if (!properties.isEnabled()) {
            return;
        }
        if (userId == null || userId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing authenticated user");
        }
        if (!canReadDevice(deviceId, userId, role, correlationId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Alert not found");
        }
    }

    public void requireAuthenticated(String userId) {
        if (properties.isEnabled() && (userId == null || userId.isBlank())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing authenticated user");
        }
    }

    public void requirePrivileged(String userId, String role) {
        requireAuthenticated(userId);
        if (!isPrivileged(role)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Support or admin role is required");
        }
    }
    public UUID accountScope(String userId) {
        requireAuthenticated(userId);
        return properties.isEnabled() ? ownAccount(userId) : null;
    }

    public UUID ownAccount(String userId) {
        requireAuthenticated(userId);
        try {
            var response=restClient.get().uri("/internal/accounts/users/{id}/lifecycle",userId)
                .header("X-Internal-Service-Token",internalServiceToken).retrieve().body(com.fasterxml.jackson.databind.JsonNode.class);
            if(response==null || !"ACTIVE".equals(response.path("status").asText()) || !userId.equals(response.path("userId").asText()))
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Active owned account required");
            return UUID.fromString(response.path("accountId").asText());
        } catch(ResponseStatusException denied) {throw denied;} catch(Exception unavailable) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Account authorization unavailable");
        }
    }
    public void workspaceAction(String authorization,UUID workspace,UUID account,UUID user,UUID device,String action) {
        var client=new com.voltcanary.common.web.WorkspaceGrantClient(restClient,internalServiceToken);
        if (!client.require(authorization,workspace,account,user,action,java.util.Set.of(device)).contains(device)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Explicit incident action grant required");
        }
    }

    private boolean isPrivileged(String role) {
        return "ADMIN".equals(role) || "SUPPORT".equals(role);
    }

    private record DeviceAccessResponse(boolean allowed) {
    }

    private record DeviceOwnerResponse(UUID accountId) {
    }

    public record DeviceRuleContext(UUID deviceId, UUID accountId, UUID locationId, String type, String status) {
    }
}
