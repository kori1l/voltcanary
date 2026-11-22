package com.voltcanary.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.common.test.SignedUserTokens;
import com.voltcanary.telemetry.service.TelemetryAccessService;
import com.voltcanary.telemetry.vehicle.ReviewedVehicleMonitoring;
import com.voltcanary.telemetry.vehicle.VehicleMonitoringSettings;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
        "app.kafka.consume-enabled=false",
        "app.kafka.publish-enabled=false",
        "app.telemetry.reconciliation.enabled=false",
        "management.health.redis.enabled=false",
    }
)
@AutoConfigureMockMvc
class VehicleMonitoringReviewsTest extends SignedUserTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    ReviewedVehicleMonitoring monitoring;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    TelemetryAccessService access;

    final UUID user = UUID.randomUUID(),
        device = UUID.randomUUID();

    String path() {
        return "/telemetry/devices/" + device + "/vehicle-monitoring";
    }

    String bearer(UUID user) {
        return "Bearer " + SignedUserTokens.create(user, "fixture@example.test", "USER");
    }

    @BeforeEach
    void authorize() {
        doAnswer(call -> {
            if (
                !user.toString().equals(call.getArgument(1))
            ) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.FORBIDDEN
            );
            return null;
        })
            .when(access)
            .requireReadAccess(any(UUID.class), anyString(), any(), any());
    }

    VehicleMonitoringSettings settings(double limit) {
        return new VehicleMonitoringSettings(
            limit,
            2,
            List.of(new VehicleMonitoringSettings.Zone(UUID.randomUUID(), "Depot", 60, 24, 200))
        );
    }

    JsonNode prepare(
        long revision,
        VehicleMonitoringSettings settings,
        String key
    ) throws Exception {
        return mapper.readTree(
            mvc
                .perform(
                    post(path() + "/reviews")
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            mapper.writeValueAsString(
                                new ReviewedVehicleMonitoring.Proposal(revision, key, settings)
                            )
                        )
                )
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString()
        );
    }

    JsonNode confirm(JsonNode review, int expected) throws Exception {
        return mapper.readTree(
            mvc
                .perform(
                    post(path() + "/reviews/" + review.path("id").asText() + "/confirm")
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            mapper.writeValueAsString(
                                new ReviewedVehicleMonitoring.Confirmation(
                                    review.path("digest").asText(),
                                    review.path("idempotencyKey").asText()
                                )
                            )
                        )
                )
                .andExpect(status().is(expected))
                .andReturn()
                .getResponse()
                .getContentAsString()
        );
    }

    @Test
    void preparationIsReadOnlyAndReplayRetainsOriginalResultAfterLaterEdit() throws Exception {
        var first = prepare(0, settings(60), "first");
        assertThat(monitoring.current(user, device).revision()).isZero();
        var saved = confirm(first, 200);
        assertThat(saved.path("appliedRevision").asLong()).isEqualTo(1);
        var second = prepare(1, settings(40), "second");
        confirm(second, 200);
        assertThat(confirm(first, 200)).isEqualTo(saved);
        assertThat(monitoring.current(user, device).settings().speedLimitKmh()).isEqualTo(40);
        assertThat(
            prepare(
                0,
                mapper.treeToValue(first.path("after"), VehicleMonitoringSettings.class),
                "first"
            ).path("id")
        ).isEqualTo(first.path("id"));
    }

    @Test
    void competingFirstSavesCannotOverwriteAndKeysCannotBeRetargeted() throws Exception {
        var a = prepare(0, settings(70), "a");
        var b = prepare(0, settings(30), "b");
        confirm(a, 200);
        confirm(b, 409);
        assertThat(monitoring.current(user, device).settings().speedLimitKmh()).isEqualTo(70);
        assertThatThrownBy(() ->
            monitoring.prepare(
                user,
                device,
                new ReviewedVehicleMonitoring.Proposal(0, "a", settings(20))
            )
        ).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test
    void expiryDenialAndReceiptInspectionDoNotWrite() throws Exception {
        var review = prepare(0, settings(55), "expiry");
        jdbc.update(
            "update telemetry.vehicle_monitoring_reviews set expires_at=? where id=?",
            java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(60)),
            UUID.fromString(review.path("id").asText())
        );
        confirm(review, 410);
        var body = mvc
            .perform(
                get(path() + "/reviews/" + review.path("id").asText()).header(
                    "Authorization",
                    bearer(user)
                )
            )
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        assertThat(mapper.readTree(body).path("status").asText()).isEqualTo("EXPIRED");
        assertThat(monitoring.current(user, device).revision()).isZero();
    }

    @Test
    void signedAccessWrongScopeAndUnreviewedWritesAreRejected() throws Exception {
        var review = prepare(0, settings(50), "scope");
        mvc.perform(
            get(path() + "/reviews/" + review.path("id").asText()).header(
                "Authorization",
                bearer(UUID.randomUUID())
            )
        ).andExpect(status().isForbidden());
        mvc.perform(get(path() + "/snapshot").header("X-User-Id", user.toString())).andExpect(
            status().isUnauthorized()
        );
        mvc.perform(
            get(
                "/telemetry/devices/" +
                    UUID.randomUUID() +
                    "/vehicle-monitoring/reviews/" +
                    review.path("id").asText()
            ).header("Authorization", bearer(user))
        ).andExpect(status().isNotFound());
        mvc.perform(
            put(path())
                .header("Authorization", bearer(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(settings(30)))
        ).andExpect(status().isBadRequest());
        assertThat(monitoring.current(user, device).revision()).isZero();
    }

    @Test
    void strictInputRejectsNumericStringsAndUnknownAuthority() throws Exception {
        for (String body : List.of(
            "{\"expectedRevision\":0,\"idempotencyKey\":\"x\",\"settings\":{\"speedLimitKmh\":\"50\",\"idleMinutes\":2,\"zones\":[]}}",
            "{\"expectedRevision\":0,\"idempotencyKey\":\"x\",\"settings\":{\"speedLimitKmh\":50,\"idleMinutes\":1.5,\"zones\":[]}}",
            "{\"expectedRevision\":0,\"idempotencyKey\":\"x\",\"userId\":\"forged\",\"settings\":{\"speedLimitKmh\":50,\"idleMinutes\":2,\"zones\":[]}}"
        ))
            mvc.perform(
                post(path() + "/reviews")
                    .header("Authorization", bearer(user))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body)
            ).andExpect(status().isBadRequest());
    }

    @Test
    void simultaneousConfirmationsCommitOneVersion() throws Exception {
        var review = monitoring.prepare(
            user,
            device,
            new ReviewedVehicleMonitoring.Proposal(0, "concurrent", settings(45))
        );
        var command = new ReviewedVehicleMonitoring.Confirmation(
            review.digest(),
            review.idempotencyKey()
        );
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> monitoring.confirm(user, device, review.id(), command));
            var b = pool.submit(() -> monitoring.confirm(user, device, review.id(), command));
            assertThat(a.get(10, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(
                b.get(10, java.util.concurrent.TimeUnit.SECONDS)
            );
        }
        assertThat(monitoring.current(user, device).revision()).isEqualTo(1);
    }
}
