package com.voltcanary.telemetry;

import com.voltcanary.telemetry.config.TelemetryAuthorizationProperties;
import com.voltcanary.telemetry.service.TelemetryAccessService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TelemetryAccessServiceTest {

    @Test
    void validInternalServiceTokenAllowsReadWithoutUserHeaders() {
        TelemetryAccessService accessService = accessService("internal-token");

        accessService.requireReadAccess(UUID.randomUUID(), null, null, "corr-1", "internal-token");
    }

    @Test
    void missingInternalServiceTokenStillRequiresAuthenticatedUser() {
        TelemetryAccessService accessService = accessService("internal-token");

        assertThatThrownBy(() -> accessService.requireReadAccess(UUID.randomUUID(), null, null, "corr-1", null))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    private TelemetryAccessService accessService(String internalServiceToken) {
        TelemetryAuthorizationProperties properties = new TelemetryAuthorizationProperties();
        properties.setEnabled(true);
        properties.setDeviceServiceBaseUrl("http://device-service");
        properties.setInternalServiceToken(internalServiceToken);
        return new TelemetryAccessService(properties, RestClient.builder());
    }
}
