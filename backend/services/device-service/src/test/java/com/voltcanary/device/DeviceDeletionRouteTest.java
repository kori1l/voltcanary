package com.voltcanary.device;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.voltcanary.device.config.InternalServiceAuth;
import com.voltcanary.device.controller.InternalController;
import com.voltcanary.device.repository.DeviceRepository;
import com.voltcanary.device.repository.DeviceTypeRepository;
import com.voltcanary.device.service.AccountService;
import com.voltcanary.device.service.DeviceDeletionService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class DeviceDeletionRouteTest {

    @Test
    void authoritativeReceiptRouteBindsDeviceIdWithoutCompilerParameterMetadata() throws Exception {
        UUID device = UUID.randomUUID();
        var deletions = mock(DeviceDeletionService.class);
        when(deletions.internalReceipt(device)).thenReturn(
            Map.of("deviceId", device, "deleteHistory", false, "sourceIds", List.of())
        );
        var controller = new InternalController(
            new com.voltcanary.device.service.InternalDeviceQueries(
                mock(DeviceRepository.class),
                mock(DeviceTypeRepository.class),
                mock(AccountService.class),
                mock(InternalServiceAuth.class),
                deletions
            )
        );
        MockMvcBuilders.standaloneSetup(controller)
            .build()
            .perform(
                get("/internal/device-deletions/" + device).header(
                    "X-Internal-Service-Token",
                    "internal-test"
                )
            )
            .andExpect(status().isOk());
    }
}
