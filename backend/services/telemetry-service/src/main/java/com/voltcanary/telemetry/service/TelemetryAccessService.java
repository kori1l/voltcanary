package com.voltcanary.telemetry.service;

import com.voltcanary.telemetry.config.TelemetryAuthorizationProperties;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
public class TelemetryAccessService {

    private static final Logger log = LoggerFactory.getLogger(TelemetryAccessService.class);

    private final TelemetryAuthorizationProperties properties;
    private final RestClient restClient;
    private final String internalServiceToken;

    public TelemetryAccessService(
            TelemetryAuthorizationProperties properties,
            RestClient.Builder restClientBuilder,
            @org.springframework.beans.factory.annotation.Value("${app.internal.service-token:${INTERNAL_SERVICE_TOKEN:dev-internal-token}}") String internalServiceToken) {
        this.properties = properties;
        this.restClient = restClientBuilder.baseUrl(properties.getDeviceServiceBaseUrl()).build();
        this.internalServiceToken = internalServiceToken;
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
            restClient.get()
                    .uri("/internal/devices/{deviceId}/access?userId={userId}", deviceId, userId)
                    .header("X-Correlation-Id", correlationId == null ? "" : correlationId)
                    .header("X-Internal-Service-Token", internalServiceToken)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (request, response) -> {
                        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Device access denied");
                    })
                    .toBodilessEntity();
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            log.warn(
                    "telemetryAccessCheckFailed deviceId={} userId={} reason={}",
                    deviceId,
                    userId,
                    exception.getClass().getSimpleName());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Device authorization unavailable");
        }
    }
}
