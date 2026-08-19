package com.voltcanary.analytics;

import com.voltcanary.analytics.repository.MaintenanceWindowReadModelRepository;
import com.voltcanary.analytics.dto.MaintenanceOutcomeSnapshot;
import com.voltcanary.analytics.ml.MlClient;
import com.voltcanary.analytics.repository.MaintenanceOutcomeReadModelRepository;
import com.voltcanary.analytics.service.MaintenanceOutcomeProjectionService;
import com.voltcanary.analytics.service.MaintenanceWindowProjectionService;
import com.voltcanary.analytics.service.AnalyticsJobService;
import com.voltcanary.analytics.service.TelemetryValidatedEventHandler;
import com.voltcanary.common.events.device.MaintenanceWindowChangedEvent;
import com.voltcanary.common.events.telemetry.TelemetryValidatedEvent;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringBootTest(properties = {
        "app.kafka.consume-enabled=false",
        "app.kafka.publish-enabled=false",
        "app.analytics.authorization.enabled=false",
        "app.analytics.outbox.reconciliation.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:analytics-consumer-replay;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true"
})
class AnalyticsConsumerReplayTest {

    @Autowired
    private TelemetryValidatedEventHandler eventHandler;

    @Autowired
    private MaintenanceWindowProjectionService maintenanceWindowProjectionService;

    @Autowired
    private MaintenanceWindowReadModelRepository maintenanceWindowRepository;

    @Autowired
    private MaintenanceOutcomeProjectionService maintenanceOutcomeProjectionService;

    @Autowired
    private MaintenanceOutcomeReadModelRepository maintenanceOutcomeRepository;

    @MockitoBean
    private AnalyticsJobService jobService;

    @MockitoBean
    private MlClient mlClient;

    @Test
    void duplicateTelemetryValidatedEventEnqueuesOnlyOneAnalyticsJob() {
        UUID eventId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        Instant timestamp = Instant.parse("2026-06-13T08:00:00Z");
        TelemetryValidatedEvent event = new TelemetryValidatedEvent(
                eventId,
                deviceId,
                "plug-001",
                timestamp,
                Map.of("activePowerW", 840.2),
                List.of(),
                timestamp.plusSeconds(1));

        boolean first = eventHandler.handle(event);
        boolean duplicate = eventHandler.handle(event);

        assertThat(first).isTrue();
        assertThat(duplicate).isFalse();
        verify(jobService, times(1)).enqueueRecalculation(eq(deviceId), eq(timestamp));
    }

    @Test
    void maintenanceWindowProjectionSkipsTelemetryAnalyticsJobEnqueue() {
        UUID deviceId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID maintenanceWindowId = UUID.randomUUID();
        Instant startAt = Instant.parse("2026-06-13T07:00:00Z");
        Instant endAt = Instant.parse("2026-06-13T09:00:00Z");
        Instant timestamp = Instant.parse("2026-06-13T08:00:00Z");
        maintenanceWindowProjectionService.project(new MaintenanceWindowChangedEvent(
                UUID.randomUUID(),
                "v1",
                "MAINTENANCE_WINDOW_CREATED",
                maintenanceWindowId,
                accountId,
                deviceId,
                "Planned work",
                null,
                startAt,
                endAt,
                true,
                true,
                true,
                null,
                null,
                1,
                null,
                "device-service",
                UUID.randomUUID(),
                startAt.minusSeconds(60)));
        clearInvocations(jobService);

        boolean processed = eventHandler.handle(new TelemetryValidatedEvent(
                UUID.randomUUID(),
                deviceId,
                "plug-001",
                timestamp,
                Map.of("activePowerW", 840.2),
                List.of(),
                timestamp.plusSeconds(1)));

        assertThat(processed).isTrue();
        verify(jobService, never()).enqueueRecalculation(eq(deviceId), eq(timestamp));
    }

    @Test
    void maintenanceWindowProjectionIgnoresOlderVersions() {
        UUID deviceId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID maintenanceWindowId = UUID.randomUUID();
        Instant startAt = Instant.parse("2026-06-13T07:00:00Z");
        Instant endAt = Instant.parse("2026-06-13T09:00:00Z");
        maintenanceWindowProjectionService.project(new MaintenanceWindowChangedEvent(
                UUID.randomUUID(),
                "v1",
                "MAINTENANCE_WINDOW_UPDATED",
                maintenanceWindowId,
                accountId,
                deviceId,
                "New title",
                null,
                startAt,
                endAt,
                true,
                true,
                true,
                null,
                null,
                2,
                null,
                "device-service",
                UUID.randomUUID(),
                startAt));
        maintenanceWindowProjectionService.project(new MaintenanceWindowChangedEvent(
                UUID.randomUUID(),
                "v1",
                "MAINTENANCE_WINDOW_CREATED",
                maintenanceWindowId,
                accountId,
                deviceId,
                "Old title",
                null,
                startAt,
                endAt,
                true,
                true,
                true,
                null,
                null,
                1,
                null,
                "device-service",
                UUID.randomUUID(),
                startAt.minusSeconds(60)));

        assertThat(maintenanceWindowRepository.findById(maintenanceWindowId)).hasValueSatisfying(window -> {
            assertThat(window.getTitle()).isEqualTo("New title");
            assertThat(window.getVersion()).isEqualTo(2);
        });
    }

    @Test
    void maintenanceOutcomeProjectionStoresOutcomeAndIgnoresOlderVersions() {
        UUID deviceId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID outcomeId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        UUID windowId = UUID.randomUUID();
        Instant observedAt = Instant.parse("2026-06-13T08:00:00Z");

        maintenanceOutcomeProjectionService.upsertSnapshot(new MaintenanceOutcomeSnapshot(
                outcomeId,
                accountId,
                deviceId,
                operationId,
                windowId,
                observedAt,
                observedAt.plusSeconds(1800),
                "CONFIRMED_FAILURE",
                "COMPRESSOR_OVERHEAT",
                "compressor",
                "HIGH",
                30,
                "Replaced compressor",
                "TECHNICIAN",
                0.95,
                UUID.randomUUID(),
                UUID.randomUUID(),
                observedAt.minusSeconds(60),
                observedAt.plusSeconds(60),
                2));
        maintenanceOutcomeProjectionService.upsertSnapshot(new MaintenanceOutcomeSnapshot(
                outcomeId,
                accountId,
                deviceId,
                operationId,
                windowId,
                observedAt,
                observedAt.plusSeconds(1200),
                "UNKNOWN",
                null,
                null,
                "LOW",
                10,
                null,
                "OPERATOR",
                0.5,
                UUID.randomUUID(),
                UUID.randomUUID(),
                observedAt.minusSeconds(120),
                observedAt,
                1));

        assertThat(maintenanceOutcomeRepository.findById(outcomeId)).hasValueSatisfying(outcome -> {
            assertThat(outcome.getOutcomeType()).isEqualTo("CONFIRMED_FAILURE");
            assertThat(outcome.getFailureMode()).isEqualTo("COMPRESSOR_OVERHEAT");
            assertThat(outcome.getVersion()).isEqualTo(2);
        });
        assertThat(maintenanceOutcomeProjectionService.observedForDevice(
                deviceId,
                observedAt.minusSeconds(1),
                observedAt.plusSeconds(1)))
                .extracting(outcome -> outcome.getMaintenanceOutcomeId())
                .containsExactly(outcomeId);
    }
}
