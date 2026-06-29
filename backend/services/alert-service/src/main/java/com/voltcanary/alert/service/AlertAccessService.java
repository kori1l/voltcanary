package com.voltcanary.alert.service;

import com.voltcanary.alert.config.AlertAuthorizationProperties;
import com.voltcanary.alert.config.AlertInternalProperties;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AlertAccessService {

    private static final Logger log = LoggerFactory.getLogger(AlertAccessService.class);

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
        if (isPrivileged(role)) {
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
            return response == null ? null : response.accountId();
        } catch (Exception exception) {
            log.warn(
                    "alertDeviceOwnerLookupFailed deviceId={} reason={}",
                    deviceId,
                    exception.getClass().getSimpleName());
            return null;
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
