package com.voltcanary.telemetry;

import com.voltcanary.telemetry.config.TelemetryValidationProperties;
import com.voltcanary.telemetry.controller.TelemetryController;
import com.voltcanary.telemetry.service.TelemetryAccessService;
import com.voltcanary.telemetry.service.TelemetryQueryService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TelemetryHistoryAuthorizationTest {
    @Test
    void intervalDiscoveryEnvelopeAndValuesRequireTheSameDeviceAuthorization() throws Exception {
        TelemetryAccessService access = mock(TelemetryAccessService.class);
        TelemetryQueryService queries = mock(TelemetryQueryService.class);
        UUID device = UUID.randomUUID();
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Device access denied"))
                .when(access).requireReadAccess(eq(device), eq("other-owner"), nullable(String.class), nullable(String.class), nullable(String.class));
        var mvc = MockMvcBuilders.standaloneSetup(new TelemetryController(queries, access, new TelemetryValidationProperties())).build();
        for (String path : List.of("metrics", "readings/envelope", "readings/export")) {
            mvc.perform(get("/telemetry/devices/" + device + "/" + path)
                    .param("from", "2026-10-01T00:00:00Z").param("to", "2026-10-08T00:00:00Z")
                    .param("metric", "temperatureC").header("X-User-Id", "other-owner"))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(queries);
    }
}
