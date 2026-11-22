package com.voltcanary.simulator;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.voltcanary.simulator.config.SimulationTargetProperties;
import com.voltcanary.simulator.domain.SimulationRun;
import com.voltcanary.simulator.repository.SimulationRunRepository;
import com.voltcanary.simulator.service.SimulationUserAccess;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.server.ResponseStatusException;

class SimulationUserAccessTest {

    @Test
    void deviceAndRunReadsForwardCurrentJwtAndFailClosedOnDeniedOrUnavailableOwner() {
        var builder = com.voltcanary.common.web.JacksonRestClients.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var properties = new SimulationTargetProperties();
        properties.setDeviceServiceBaseUrl("http://devices");
        var runs = mock(SimulationRunRepository.class);
        var access = new SimulationUserAccess(properties, builder, runs);
        UUID device = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        var run = new SimulationRun();
        run.setDeviceId(device);
        when(runs.findById(runId)).thenReturn(Optional.of(run));
        server
            .expect(requestTo("http://devices/devices/" + device))
            .andExpect(header("Authorization", "Bearer current"))
            .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server
            .expect(requestTo("http://devices/devices/" + device))
            .andExpect(header("Authorization", "Bearer revoked"))
            .andRespond(withStatus(HttpStatus.FORBIDDEN));
        server
            .expect(requestTo("http://devices/devices/" + device))
            .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        access.requireRun("Bearer current", runId);
        assertThatThrownBy(() -> access.requireRun("Bearer revoked", runId)).isInstanceOfSatisfying(
            ResponseStatusException.class,
            failure ->
                org.assertj.core.api.Assertions.assertThat(failure.getStatusCode()).isEqualTo(
                    HttpStatus.NOT_FOUND
                )
        );
        assertThatThrownBy(() ->
            access.requireDevice("Bearer current", device)
        ).isInstanceOfSatisfying(ResponseStatusException.class, failure ->
            org.assertj.core.api.Assertions.assertThat(failure.getStatusCode()).isEqualTo(
                HttpStatus.SERVICE_UNAVAILABLE
            )
        );
        server.verify();
    }
}
