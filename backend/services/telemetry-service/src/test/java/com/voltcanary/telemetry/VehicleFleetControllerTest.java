package com.voltcanary.telemetry;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.voltcanary.common.web.VehiclePositionTargets;
import com.voltcanary.telemetry.adapter.PositionSourceAccess;
import com.voltcanary.telemetry.controller.VehicleFleetController;
import com.voltcanary.telemetry.service.PositionReads;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VehicleFleetControllerTest {

    @Test
    void authorizesWholeBatchBeforeReadingAndRejectsOversizedInput() {
        var reads = mock(PositionReads.class);
        var sources = mock(PositionSourceAccess.class);
        var controller = new VehicleFleetController(reads, sources);
        var targets = List.of(new VehiclePositionTargets.Target(UUID.randomUUID(), "gps"));
        doThrow(
            new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.FORBIDDEN
            )
        )
            .when(sources)
            .requireFleet("user", targets);
        assertThatThrownBy(() ->
            controller.latest(new VehicleFleetController.Request(targets, null), "user", null)
        ).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        verifyNoInteractions(reads);
        assertThatThrownBy(() ->
            controller.latest(
                new VehicleFleetController.Request(
                    java.util.Collections.nCopies(101, targets.getFirst()),
                    null
                ),
                "user",
                null
            )
        ).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
}
