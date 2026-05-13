package com.voltcanary.telemetry;

import com.voltcanary.common.events.telemetry.TelemetryReceivedEvent;
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

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.kafka.consume-enabled=false",
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
                        "rssi", -61,
                        "relayState", "ON"),
                Instant.now());
    }
}
