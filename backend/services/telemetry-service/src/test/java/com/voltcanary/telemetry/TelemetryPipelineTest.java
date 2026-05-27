package com.voltcanary.telemetry;

import com.voltcanary.common.events.telemetry.TelemetryReceivedEvent;
import com.voltcanary.telemetry.producer.TelemetryDeadLetterPublisher;
import com.voltcanary.telemetry.producer.TelemetryValidatedPublisher;
import com.voltcanary.telemetry.service.TelemetryEventProcessor;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.kafka.consume-enabled=false",
                "app.telemetry.authorization.enabled=false",
                "app.telemetry.validation.max-past-age=30d",
                "spring.kafka.bootstrap-servers=localhost:19092"
        })
class TelemetryPipelineTest {

    @Autowired
    private TelemetryEventProcessor telemetryEventProcessor;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private TelemetryValidatedPublisher telemetryValidatedPublisher;

    @MockitoBean
    private TelemetryDeadLetterPublisher telemetryDeadLetterPublisher;

    @Test
    void consumesEventSavesReadingsAndExposesLatestAndChartPoints() {
        UUID deviceId = UUID.randomUUID();
        Instant timestamp = Instant.parse("2026-05-12T12:30:00Z");

        int inserted = telemetryEventProcessor.process(event(deviceId, "plug-001", timestamp));

        assertThat(inserted).isEqualTo(5);
        Long rowCount = jdbcTemplate.queryForObject(
                "select count(*) from telemetry.measurements where device_id = ?",
                Long.class,
                deviceId);
        assertThat(rowCount).isEqualTo(5);
        verify(telemetryValidatedPublisher).publish(any());

        ResponseEntity<String> latest = restTemplate.getForEntity(
                "/telemetry/devices/{deviceId}/latest",
                String.class,
                deviceId);
        assertThat(latest.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(latest.getBody()).contains("\"activePowerW\":840.2");
        assertThat(latest.getBody()).contains("\"timestamp\":\"2026-05-12T12:30:00Z\"");

        ResponseEntity<String> chart = restTemplate.getForEntity(
                "/telemetry/devices/{deviceId}/readings?metric=activePowerW&from=2026-05-12T12:00:00Z&to=2026-05-12T13:00:00Z",
                String.class,
                deviceId);
        assertThat(chart.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(chart.getBody()).contains("\"metric\":\"activePowerW\"");
        assertThat(chart.getBody()).contains("\"value\":840.2");

        ResponseEntity<String> aggregatedChart = restTemplate.getForEntity(
                "/telemetry/devices/{deviceId}/readings?metric=activePowerW&from=2026-05-12T12:00:00Z&to=2026-05-12T13:00:00Z&interval=1m",
                String.class,
                deviceId);
        assertThat(aggregatedChart.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(aggregatedChart.getBody()).contains("\"metric\":\"activePowerW\"");

        ResponseEntity<String> metrics = restTemplate.getForEntity(
                "/telemetry/devices/{deviceId}/metrics",
                String.class,
                deviceId);
        assertThat(metrics.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(metrics.getBody()).contains("\"name\":\"activePowerW\"");
        assertThat(metrics.getBody()).contains("\"unit\":\"W\"");

        ResponseEntity<String> export = restTemplate.getForEntity(
                "/telemetry/devices/{deviceId}/readings/export?metric=activePowerW&from=2026-05-12T12:00:00Z&to=2026-05-12T13:00:00Z&limit=1",
                String.class,
                deviceId);
        assertThat(export.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(export.getBody()).contains("\"points\"");
        assertThat(export.getBody()).contains("\"value\":840.2");
    }

    @Test
    void rejectsChartQueryRangeAboveConfiguredLimit() {
        UUID deviceId = UUID.randomUUID();

        ResponseEntity<String> chart = restTemplate.getForEntity(
                "/telemetry/devices/{deviceId}/readings?metric=activePowerW&from=2026-01-01T00:00:00Z&to=2026-05-12T13:00:00Z",
                String.class,
                deviceId);

        assertThat(chart.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void duplicateEventDoesNotCreateDuplicateRows() {
        UUID deviceId = UUID.randomUUID();
        Instant timestamp = Instant.parse("2026-05-12T12:31:00Z");
        TelemetryReceivedEvent event = event(deviceId, "plug-001", timestamp);

        int firstInsert = telemetryEventProcessor.process(event);
        int duplicateInsert = telemetryEventProcessor.process(event);

        assertThat(firstInsert).isEqualTo(5);
        assertThat(duplicateInsert).isZero();
        Long rowCount = jdbcTemplate.queryForObject(
                "select count(*) from telemetry.measurements where device_id = ?",
                Long.class,
                deviceId);
        assertThat(rowCount).isEqualTo(5);
        verify(telemetryValidatedPublisher, times(1)).publish(any());

        ResponseEntity<String> quality = restTemplate.getForEntity(
                "/telemetry/devices/{deviceId}/quality",
                String.class,
                deviceId);
        assertThat(quality.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(quality.getBody()).contains("DUPLICATE");
    }

    @Test
    void invalidMetricsArePersistedAsQualityAndSentToDlq() {
        UUID deviceId = UUID.randomUUID();
        TelemetryReceivedEvent event = new TelemetryReceivedEvent(
                UUID.randomUUID(),
                deviceId,
                "plug-001",
                Instant.parse("2026-05-12T12:32:00Z"),
                Map.of("voltageV", 5000.0, "relayState", "ON"),
                Instant.now());

        int inserted = telemetryEventProcessor.process(event);

        assertThat(inserted).isZero();
        Long rowCount = jdbcTemplate.queryForObject(
                "select count(*) from telemetry.measurements where device_id = ?",
                Long.class,
                deviceId);
        assertThat(rowCount).isZero();
        verify(telemetryDeadLetterPublisher).publish(any(), any(), any());

        ResponseEntity<String> quality = restTemplate.getForEntity(
                "/telemetry/devices/{deviceId}/quality",
                String.class,
                deviceId);
        assertThat(quality.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(quality.getBody()).contains("OUT_OF_RANGE");
        assertThat(quality.getBody()).contains("INVALID_VALUE");
    }

    @Test
    void partiallyInvalidMetricsStillPersistValidMeasurements() {
        UUID deviceId = UUID.randomUUID();
        TelemetryReceivedEvent event = new TelemetryReceivedEvent(
                UUID.randomUUID(),
                deviceId,
                "plug-001",
                Instant.parse("2026-05-12T12:33:00Z"),
                Map.of("activePowerW", 120.0, "voltageV", 230.0, "relayState", "ON"),
                Instant.now());

        int inserted = telemetryEventProcessor.process(event);

        assertThat(inserted).isEqualTo(2);
        Long rowCount = jdbcTemplate.queryForObject(
                "select count(*) from telemetry.measurements where device_id = ?",
                Long.class,
                deviceId);
        assertThat(rowCount).isEqualTo(2);
        verify(telemetryValidatedPublisher).publish(any());

        ResponseEntity<String> quality = restTemplate.getForEntity(
                "/telemetry/devices/{deviceId}/quality",
                String.class,
                deviceId);
        assertThat(quality.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(quality.getBody()).contains("INVALID_VALUE");
    }

    @Test
    void outOfOrderEventIsAcceptedAndFlagged() {
        UUID deviceId = UUID.randomUUID();
        telemetryEventProcessor.process(event(deviceId, "plug-001", Instant.parse("2026-05-12T12:40:00Z")));

        int inserted = telemetryEventProcessor.process(event(deviceId, "plug-001", Instant.parse("2026-05-12T12:39:00Z")));

        assertThat(inserted).isEqualTo(5);
        ResponseEntity<String> quality = restTemplate.getForEntity(
                "/telemetry/devices/{deviceId}/quality",
                String.class,
                deviceId);
        assertThat(quality.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(quality.getBody()).contains("OUT_OF_ORDER");
    }

    @Test
    void backfillSourceCanPersistHistoricalTelemetryWithoutRealtimePublish() {
        UUID deviceId = UUID.randomUUID();
        Instant recordedAt = Instant.parse("2026-01-12T12:30:00Z");

        int inserted = telemetryEventProcessor.process(event(deviceId, "backfill-csv-001", recordedAt));

        assertThat(inserted).isEqualTo(5);
        Long rowCount = jdbcTemplate.queryForObject(
                "select count(*) from telemetry.measurements where device_id = ? and received_at is not null",
                Long.class,
                deviceId);
        assertThat(rowCount).isEqualTo(5);
        verify(telemetryValidatedPublisher, never()).publish(any());

        ResponseEntity<String> quality = restTemplate.getForEntity(
                "/telemetry/devices/{deviceId}/quality",
                String.class,
                deviceId);
        assertThat(quality.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(quality.getBody()).contains("BACKFILL");
    }

    @Test
    void storedReadingPayloadKeepsOnlySupportedTelemetryMetrics() {
        UUID deviceId = UUID.randomUUID();
        telemetryEventProcessor.process(new TelemetryReceivedEvent(
                UUID.randomUUID(),
                deviceId,
                "plug-001",
                Instant.parse("2026-05-12T12:50:00Z"),
                Map.of("activePowerW", 840.2, "vendorSecret", "do-not-store"),
                Instant.now()));

        String metricsJson = jdbcTemplate.queryForObject(
                "select metrics_json from telemetry_readings where device_id = ?",
                String.class,
                deviceId);
        assertThat(metricsJson).contains("activePowerW");
        assertThat(metricsJson).doesNotContain("vendorSecret");
    }

    private TelemetryReceivedEvent event(UUID deviceId, String sourceId, Instant timestamp) {
        return new TelemetryReceivedEvent(
                UUID.randomUUID(),
                deviceId,
                sourceId,
                timestamp,
                Map.of(
                        "activePowerW", 840.2,
                        "voltageV", 230.1,
                        "currentA", 3.65,
                        "energyWh", 12450.7,
                        "rssi", -61),
                Instant.now());
    }
}
