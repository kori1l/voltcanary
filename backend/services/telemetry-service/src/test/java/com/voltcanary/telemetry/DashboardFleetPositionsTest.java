package com.voltcanary.telemetry;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.voltcanary.common.web.DashboardOwnerReadRequest;
import com.voltcanary.common.web.DashboardPositionReadRequest;
import com.voltcanary.telemetry.adapter.DashboardReadAuthorization;
import com.voltcanary.telemetry.adapter.PositionSourceAccess;
import com.voltcanary.telemetry.controller.DashboardPositionController;
import com.voltcanary.telemetry.service.PositionReads;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DashboardFleetPositionsTest {

    @Test
    void authorizesWholeScopeAndAllSourcesBeforeAnyPositions() {
        var first = UUID.randomUUID();
        var second = UUID.randomUUID();
        var now = Instant.now();
        var scope = new DashboardOwnerReadRequest(
            UUID.randomUUID(),
            UUID.randomUUID(),
            List.of(first, second),
            new DashboardOwnerReadRequest.TimeFrame(now.minusSeconds(60), now, now, null, null),
            Set.of(),
            List.of(),
            1,
            0,
            "ACTIVE",
            List.of()
        );
        var access = mock(DashboardReadAuthorization.class);
        var sources = mock(PositionSourceAccess.class);
        var reads = mock(PositionReads.class);
        var controller = new DashboardPositionController(access, sources, reads);
        var request = new DashboardPositionReadRequest(
            scope,
            null,
            null,
            Map.of(first, "gps-a", second, "gps-b")
        );
        when(access.authorize("internal", "bearer", scope)).thenReturn(List.of(first));
        assertThatThrownBy(() -> controller.read("internal", "bearer", request)).isInstanceOf(
            org.springframework.web.server.ResponseStatusException.class
        );
        verifyNoInteractions(reads, sources);
        when(access.authorize("internal", "bearer", scope)).thenReturn(List.of(first, second));
        doThrow(new IllegalArgumentException("source removed"))
            .when(sources)
            .require(second, "gps-b");
        assertThatThrownBy(() -> controller.read("internal", "bearer", request)).isInstanceOf(
            IllegalArgumentException.class
        );
        verifyNoInteractions(reads);
        reset(sources);
        controller.read("internal", "bearer", request);
        verify(reads).fleetEvidence(request.vehicleSources(), now.minusSeconds(60), now, now);
        assertThatThrownBy(() ->
            controller.read(
                "internal",
                "bearer",
                new DashboardPositionReadRequest(scope, null, null, Map.of(first, "gps-a"))
            )
        ).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
}
