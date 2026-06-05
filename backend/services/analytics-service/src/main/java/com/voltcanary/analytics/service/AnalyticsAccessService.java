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

    private record DeviceAccessResponse(boolean allowed) {
    }
}
