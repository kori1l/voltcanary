package com.voltcanary.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.voltcanary.common.test.SignedUserTokens;
import com.voltcanary.telemetry.service.TelemetryAccessService;
import com.voltcanary.telemetry.vehicle.VehicleMonitoringRepository;
import com.voltcanary.telemetry.vehicle.VehicleMonitoringSettings;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(
    properties = {
        "app.kafka.consume-enabled=false",
        "app.kafka.publish-enabled=false",
        "app.telemetry.reconciliation.enabled=false",
        "management.health.redis.enabled=false",
    }
)
@AutoConfigureMockMvc
class VehicleMonitoringApiTest extends SignedUserTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    VehicleMonitoringRepository repository;

    @Autowired
    com.voltcanary.telemetry.vehicle.ReviewedVehicleMonitoring monitoring;

    @MockitoBean
    TelemetryAccessService access;

    private final UUID owner = UUID.randomUUID(),
        device = UUID.randomUUID();

    private String bearer(UUID user) {
        return "Bearer " + SignedUserTokens.create(user, "fixture@example.test", "USER");
    }

    private String path() {
        return "/telemetry/devices/" + device + "/vehicle-monitoring";
    }

    @BeforeEach
    void authorizeOwner() {
        doAnswer(call -> {
            if (!owner.toString().equals(call.getArgument(1))) throw new ResponseStatusException(
                HttpStatus.FORBIDDEN
            );
            return null;
        })
            .when(access)
            .requireReadAccess(any(UUID.class), anyString(), any(), any());
    }

    @Test
    void rejectsFractionalIdleAndUnknownAuthorityFieldsWithoutChangingSettings() throws Exception {
        for (String body : new String[] {
            "{\"speedLimitKmh\":80,\"idleMinutes\":1.5,\"zones\":[]}",
            "{\"speedLimitKmh\":80,\"idleMinutes\":1,\"zones\":[],\"ownerUserId\":\"forged\"}",
        }) {
            mvc.perform(
                put(path())
                    .header("Authorization", bearer(owner))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body)
            ).andExpect(status().isBadRequest());
            assertThat(repository.get(owner, device)).isEqualTo(
                VehicleMonitoringSettings.defaults()
            );
        }
    }

    @Test
    void persistsValidNumericSettingsAndRejectsOutOfRangeValues() throws Exception {
        var review = monitoring.prepare(
            owner,
            device,
            new com.voltcanary.telemetry.vehicle.ReviewedVehicleMonitoring.Proposal(
                0,
                "numeric",
                new VehicleMonitoringSettings(35.5, 1, java.util.List.of())
            )
        );
        monitoring.confirm(
            owner,
            device,
            review.id(),
            new com.voltcanary.telemetry.vehicle.ReviewedVehicleMonitoring.Confirmation(
                review.digest(),
                review.idempotencyKey()
            )
        );
        mvc.perform(get(path()).header("Authorization", bearer(owner)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.speedLimitKmh").value(35.5));
        mvc.perform(
            put(path())
                .header("Authorization", bearer(owner))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"speedLimitKmh\":0,\"idleMinutes\":1,\"zones\":[]}")
        ).andExpect(status().isBadRequest());
        assertThat(repository.get(owner, device).speedLimitKmh()).isEqualTo(35.5);
    }

    @Test
    void deniesForeignAndUnsignedRequestsBeforeSettingsAccess() throws Exception {
        mvc.perform(get(path()).header("Authorization", bearer(UUID.randomUUID()))).andExpect(
            status().isForbidden()
        );
        mvc.perform(get(path()).header("X-User-Id", owner.toString())).andExpect(
            status().isUnauthorized()
        );
    }
}
