package com.voltcanary.alert;

import com.voltcanary.alert.config.AlertAuthorizationProperties;
import com.voltcanary.alert.config.AlertInternalProperties;
import com.voltcanary.alert.service.AlertAccessService;
import com.voltcanary.common.web.JacksonRestClients;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AlertOwnerLookupTest {
    @Test void ownerOutagesAndMissingOwnersCannotBecomePermanentlyUnscopedAlerts() {
        var builder = JacksonRestClients.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var properties = new AlertAuthorizationProperties();
        var access = new AlertAccessService(properties, builder, new AlertInternalProperties());
        UUID device = UUID.randomUUID();
        String url = properties.getDeviceServiceBaseUrl() + "/internal/devices/" + device + "/owner";
        server.expect(requestTo(url)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        assertUnavailable(() -> access.resolveDeviceAccountId(device, "test"));
        server.verify();
        server.reset();
        server.expect(requestTo(url)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        assertUnavailable(() -> access.resolveDeviceAccountId(device, "test"));
        server.verify();
    }

    private void assertUnavailable(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ResponseStatusException.class,
                error -> org.assertj.core.api.Assertions.assertThat(error.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }
}
