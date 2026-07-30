package com.voltcanary.analytics.service;

import com.voltcanary.analytics.config.AnalyticsAuthorizationProperties;
import com.voltcanary.analytics.config.AnalyticsInternalProperties;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AnalyticsAccessService {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsAccessService.class);

    private final AnalyticsAuthorizationProperties properties;
    private final RestClient restClient;
    private final String internalServiceToken;
    public AnalyticsAccessService(
            AnalyticsAuthorizationProperties properties,
            RestClient.Builder restClientBuilder,
            AnalyticsInternalProperties internalProperties) {
        this.properties = properties;
        this.internalServiceToken = internalProperties.getServiceToken();
        this.restClient = restClientBuilder
                .baseUrl(properties.getDeviceServiceBaseUrl())
                .build();
    }

    public void requireReadAccess(UUID deviceId, String userId, String role, String correlationId) {
        if (!properties.isEnabled()) {
            return;
        }
        if (userId == null || userId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing authenticated user");
        }
        if ("ADMIN".equals(role) || "SUPPORT".equals(role)) {
            return;
        }
        try {
            DeviceAccessResponse response = restClient.get()
                    .uri("/internal/devices/{deviceId}/access?userId={userId}", deviceId, userId)
                    .header("X-Correlation-Id", correlationId == null ? "" : correlationId)
                    .header("X-Internal-Service-Token", internalServiceToken)
                    .retrieve()
                    .body(DeviceAccessResponse.class);
            if (response == null || !response.allowed()) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Device access denied");
            }
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            log.warn(
                    "analyticsAccessCheckFailed deviceId={} userId={} reason={}",
                    deviceId,
                    userId,
                    exception.getClass().getSimpleName());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Device authorization unavailable");
        }
    }

    public UUID accountIdForUser(String userId, String correlationId) {
        UUID parsedUserId = parseUserId(userId);
        try {
            AccountLifecycleResponse response = restClient.get()
                    .uri("/internal/accounts/users/{userId}/lifecycle", parsedUserId)
                    .header("X-Correlation-Id", correlationId == null ? "" : correlationId)
                    .header("X-Internal-Service-Token", internalServiceToken)
                    .retrieve()
                    .body(AccountLifecycleResponse.class);
            if (response == null || response.accountId() == null) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Account access denied");
            }
            return response.accountId();
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            log.warn(
                    "analyticsAccountLookupFailed userId={} reason={}",
                    userId,
                    exception.getClass().getSimpleName());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Account authorization unavailable");
        }
    }

    public UUID accountIdForDevice(UUID deviceId, String correlationId) {
        try {
            DeviceOwnerResponse response = restClient.get()
                    .uri("/internal/devices/{deviceId}/owner", deviceId)
                    .header("X-Correlation-Id", correlationId == null ? "" : correlationId)
                    .header("X-Internal-Service-Token", internalServiceToken)
                    .retrieve()
                    .body(DeviceOwnerResponse.class);
            if (response == null || response.accountId() == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Device not found");
            }
            return response.accountId();
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            log.warn(
                    "analyticsDeviceOwnerLookupFailed deviceId={} reason={}",
                    deviceId,
                    exception.getClass().getSimpleName());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Device ownership unavailable");
        }
    }

    private UUID parseUserId(String userId) {
        try {
            return UUID.fromString(userId);
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing authenticated user");
        }
    }

    private record DeviceAccessResponse(boolean allowed) {
    }

    private record DeviceOwnerResponse(UUID deviceId, UUID accountId) {
    }

    private record AccountLifecycleResponse(UUID userId, UUID accountId, String status) {
    }
}
