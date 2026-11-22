package com.voltcanary.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.voltcanary.analytics.config.AnalyticsDownstreamProperties;
import com.voltcanary.analytics.config.AnalyticsInternalProperties;
import com.voltcanary.analytics.service.AlertRuleClient;
import com.voltcanary.common.web.JacksonRestClients;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.server.ResponseStatusException;

class AlertRuleClientTest {

    @Test
    void anEmptySnapshotIsAcceptedButAnUnavailableSnapshotFailsClosed() {
        var builder = JacksonRestClients.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var properties = new AnalyticsDownstreamProperties();
        var client = new AlertRuleClient(builder, properties, new AnalyticsInternalProperties());
        var device = UUID.randomUUID();
        String uri =
            properties.getAlertService().getBaseUrl() +
            "/internal/alert-rules/effective?deviceId=" +
            device;
        server.expect(requestTo(uri)).andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        assertThat(client.effectiveRules(device, "test")).isEmpty();
        server.verify();
        server.reset();
        server.expect(requestTo(uri)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        assertThatThrownBy(() -> client.effectiveRules(device, "test")).isInstanceOfSatisfying(
            ResponseStatusException.class,
            error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
        );
        server.verify();
    }
}
