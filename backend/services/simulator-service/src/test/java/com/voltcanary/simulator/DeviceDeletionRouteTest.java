package com.voltcanary.simulator;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.voltcanary.common.web.DeviceDeletionAuthorization;
import com.voltcanary.simulator.controller.DeviceHistoryDeletionController;
import com.voltcanary.simulator.service.DeviceHistoryDeletion;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class DeviceDeletionRouteTest {

    @Test
    void internalRouteBindsDeviceIdAndUsesAuthoritativePolicy() throws Exception {
        UUID device = UUID.randomUUID();
        var authorization = mock(DeviceDeletionAuthorization.class);
        var history = mock(DeviceHistoryDeletion.class);
        when(authorization.requireDeletion(device, "internal-test")).thenReturn(
            new DeviceDeletionAuthorization.Deletion(false, List.of())
        );
        var http = MockMvcBuilders.standaloneSetup(
            new DeviceHistoryDeletionController(history, authorization)
        ).build();
        http.perform(
            post("/internal/device-deletions/" + device).header(
                "X-Internal-Service-Token",
                "internal-test"
            )
        ).andExpect(status().isOk());
        verify(history).close(device, false);
    }
}
