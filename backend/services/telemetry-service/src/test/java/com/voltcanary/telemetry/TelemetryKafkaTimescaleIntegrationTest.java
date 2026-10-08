package com.voltcanary.telemetry;

import com.voltcanary.common.events.telemetry.TelemetryReceivedEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.sql.Timestamp;
import org.springframework.jdbc.core.JdbcTemplate;
import com.voltcanary.telemetry.dto.TelemetryEnvelopeResponse;
import com.voltcanary.telemetry.dto.TelemetryExportResponse;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.redpanda.RedpandaContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.kafka.consume-enabled=true",
                "app.telemetry.authorization.enabled=false",
                "app.telemetry.validation.redis-rate-limit-enabled=false",
                "app.telemetry.validation.max-past-age=30d",
                "management.health.redis.enabled=false",
                "spring.kafka.consumer.group-id=telemetry-integration-test",
                "spring.jpa.hibernate.ddl-auto=none"
        })
class TelemetryKafkaTimescaleIntegrationTest {

    @Container
    static final RedpandaContainer kafka = new RedpandaContainer(DockerImageName.parse("redpandadata/redpanda:v24.2.18"));

    @Container
    static final PostgreSQLContainer<?> timescale = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:2.17.2-pg16").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void infrastructureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("spring.datasource.url", timescale::getJdbcUrl);
        registry.add("spring.datasource.username", timescale::getUsername);
        registry.add("spring.datasource.password", timescale::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private com.voltcanary.telemetry.repository.DashboardTelemetryRepository dashboardReads;

    @Test
    void dashboardReducersUseFullHalfOpenScopeAndPreserveZeroLateExtremaAndQuality() {
        UUID account = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        Instant from = Instant.parse("2026-10-01T00:00:00Z");
        Instant to = from.plusSeconds(86400);
        List<UUID> devices = new ArrayList<>();
        List<Object[]> rows = new ArrayList<>();
        for (int index = 0; index < 250; index++) {
            UUID device = UUID.randomUUID(); devices.add(device);
            rows.add(new Object[]{account, device, "fixture", Timestamp.from(from), "activePowerW", 0, "W", "BACKFILL", Timestamp.from(to), Timestamp.from(to)});
            rows.add(new Object[]{account, device, "fixture", Timestamp.from(to.minusSeconds(1)), "activePowerW", index == 249 ? 1000 : 0, "W", "OK", Timestamp.from(to), Timestamp.from(to)});
            rows.add(new Object[]{account, device, "fixture", Timestamp.from(to), "activePowerW", 9999, "W", "OK", Timestamp.from(to), Timestamp.from(to)});
        }
        jdbc.batchUpdate("insert into telemetry.measurements(account_id,device_id,source_id,recorded_at,metric_name,metric_value,unit,quality,received_at,created_at) values(?,?,?,?,?,?,?,?,?,?)", rows);
        var frame = new com.voltcanary.common.web.DashboardOwnerReadRequest.TimeFrame(from, to, to, null, null);
        var request = new com.voltcanary.common.web.DashboardOwnerReadRequest(user, account, devices, frame,
                java.util.Set.of("activePowerW"), List.of(devices.getLast()), 20, 0, "ACTIVE",
                List.of(new com.voltcanary.common.web.DashboardOwnerReadRequest.Series(devices.getLast(), "activePowerW")));
        var result = dashboardReads.read(request, devices);
        var summary = result.path("summaries").get(0);
        assertThat(summary.path("sampleCount").asLong()).isEqualTo(500);
        assertThat(summary.path("deviceCount").asLong()).isEqualTo(250);
        assertThat(summary.path("minimum").asDouble()).isZero();
        assertThat(summary.path("maximum").asDouble()).isEqualTo(1000);
        assertThat(summary.path("mean").asDouble()).isEqualTo(2);
        assertThat(summary.path("p95").asDouble()).isZero();
        assertThat(summary.path("maximumAt").asText()).isEqualTo(to.minusSeconds(1).toString());
        assertThat(result.path("measurementQualityCounts").path("BACKFILL").asInt()).isEqualTo(250);
        assertThat(result.path("series").get(0).path("buckets").toString()).contains("1000.0");
        assertThat(result.path("latest").get(0).path("value").asDouble()).isEqualTo(9999);
        assertThat(restTemplate.postForEntity("/internal/dashboard/telemetry", request, String.class).getStatusCode().is4xxClientError()).isTrue();
    }

    @Test
    void simultaneousSourcesDoNotTurnNormalCadenceIntoMissingDataGaps() {
        UUID device = UUID.randomUUID();
        Instant from = Instant.now().minusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        List<Object[]> rows = new ArrayList<>();
        for (int index = 0; index < 7; index++) for (String source : List.of("source-A", "source-B", "source-C")) {
            Instant at = from.plusSeconds(index * 300L);
            rows.add(new Object[]{device, source, Timestamp.from(at), "temperatureC", source.equals("source-A") ? 0 : source.equals("source-B") ? 1 : 2, "C", "OK", Timestamp.from(at), Timestamp.from(at)});
        }
        jdbc.batchUpdate("""
                insert into telemetry.measurements(device_id,source_id,recorded_at,metric_name,metric_value,unit,quality,received_at,created_at)
                values(?,?,?,?,?,?,?,?,?)
                """, rows);
        TelemetryEnvelopeResponse result = restTemplate.getForObject("/telemetry/devices/" + device
                + "/readings/envelope?metric=temperatureC&from=" + from + "&to=" + from.plusSeconds(1800), TelemetryEnvelopeResponse.class);
        assertThat(result.rawPointCount()).isEqualTo(21);
        assertThat(result.summary().gapCount()).isZero();
        assertThat(result.summary().lastValue()).isEqualTo(2.0);
    }

    @Test
    void intervalDiscoveryAndEnvelopeCoverLateExtremaWithoutLoadingAllValues() {
        UUID device = UUID.randomUUID();
        Instant to = Instant.now().minusSeconds(1).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        Instant from = to.minus(Duration.ofDays(30));
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 2400; i++) {
            double value = i == 1500 ? 90 : i == 1800 ? -20 : i == 2300 ? 100 : 0;
            Instant at = from.plusMillis((Duration.ofDays(30).toMillis() - 7_201_000) * i / 2399 + (i >= 1200 ? 7_200_000 : 0));
            rows.add(new Object[]{device, "import-fixture", Timestamp.from(at), "temperatureC", value, "C", "BACKFILL", Timestamp.from(at), Timestamp.from(at)});
        }
        jdbc.batchUpdate("""
                insert into telemetry.measurements(device_id,source_id,recorded_at,metric_name,metric_value,unit,quality,received_at,created_at)
                values(?,?,?,?,?,?,?,?,?)
                """, rows);
        jdbc.update("""
                insert into telemetry.measurements(device_id,source_id,recorded_at,metric_name,metric_value,unit,quality,received_at,created_at)
                values(?,?,?,?,?,?,?,?,?)
                """, device, "fixture", Timestamp.from(to), "activePowerW", 10, "W", "OK", Timestamp.from(to), Timestamp.from(to));
        String base = "/telemetry/devices/" + device;
        assertThat(restTemplate.getForObject(base + "/latest", String.class)).doesNotContain("temperatureC");
        String range = "?from=" + from + "&to=" + to;
        assertThat(restTemplate.getForObject(base + "/metrics" + range, String.class)).contains("temperatureC");
        TelemetryEnvelopeResponse response = restTemplate.getForObject(base + "/readings/envelope" + range + "&metric=temperatureC&buckets=24", TelemetryEnvelopeResponse.class);
        assertThat(response.rawPointCount()).isEqualTo(2400);
        assertThat(response.points()).hasSizeLessThanOrEqualTo(96);
        assertThat(response.points().stream().map(point -> point.value())).contains(90.0, -20.0, 100.0, 0.0);
        assertThat(response.summary().min()).isEqualTo(-20);
        assertThat(response.summary().max()).isEqualTo(100);
        assertThat(response.summary().average()).isCloseTo(170.0 / 2400, org.assertj.core.data.Offset.offset(0.000001));
        assertThat(response.summary().gapCount()).isEqualTo(1);
        assertThat(response.qualityCounts()).containsEntry("BACKFILL", 2400L);
        assertThat(restTemplate.getForObject(base + "/metrics?from=" + to.plusSeconds(1) + "&to=" + to.plusSeconds(60), String.class)).isEqualTo("[]");
        assertThat(restTemplate.getForEntity(base + "/readings/envelope" + range + "&metric=temperatureC&buckets=251", String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(restTemplate.getForEntity(base + "/metrics?from=" + from + "&to=" + from.plus(Duration.ofDays(31)), String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        for (String source : List.of("alpha", "omega")) jdbc.update("""
                insert into telemetry.measurements(device_id,source_id,recorded_at,metric_name,metric_value,unit,quality,received_at,created_at)
                values(?,?,?,?,?,?,?,?,?)
                """, device, source, Timestamp.from(to), "activePowerW", source.equals("alpha") ? 0 : 20, "W", "OK", Timestamp.from(to), Timestamp.from(to));
        String next = "";
        List<Double> exported = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            TelemetryExportResponse values = restTemplate.getForObject(base + "/readings/export" + range + "&metric=activePowerW&limit=1" + next, TelemetryExportResponse.class);
            exported.add(values.points().getFirst().value());
            assertThat(values.points().getFirst().sourceId()).isNotBlank();
            assertThat(values.points().getFirst().quality()).isEqualTo("OK");
            next = "&cursor=" + values.nextCursor() + "&cursorSourceId=" + values.nextCursorSourceId();
            if (page == 2) assertThat(values.nextCursor()).isNull();
        }
        assertThat(exported).containsExactly(0.0, 10.0, 20.0);
    }

    @Test
    void consumesKafkaEventSavesTimescaleRowsAndReturnsLatestReading() throws Exception {
        UUID deviceId = UUID.randomUUID();
        Instant timestamp = Instant.now().minusSeconds(60);
        publish(new TelemetryReceivedEvent(
                UUID.randomUUID(),
                deviceId,
                "plug-001",
                timestamp,
                Map.of("activePowerW", 840.2, "voltageV", 230.1),
                Instant.now()));

        ResponseEntity<String> latest = waitForLatest(deviceId);

        assertThat(latest.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(latest.getBody()).contains("\"activePowerW\":840.2");
        assertThat(latest.getBody()).contains("\"voltageV\":230.1");
    }

    private void publish(TelemetryReceivedEvent event) throws Exception {
        Properties properties = new Properties();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        try (KafkaProducer<String, TelemetryReceivedEvent> producer = new KafkaProducer<>(properties)) {
            producer.send(new ProducerRecord<>("telemetry.raw.v1", event.deviceId().toString(), event)).get();
        }
    }

    private ResponseEntity<String> waitForLatest(UUID deviceId) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        ResponseEntity<String> latest = null;
        while (System.nanoTime() < deadline) {
            latest = restTemplate.getForEntity("/telemetry/devices/{deviceId}/latest", String.class, deviceId);
            if (latest.getStatusCode() == HttpStatus.OK && latest.getBody() != null && latest.getBody().contains("activePowerW")) {
                return latest;
            }
            Thread.sleep(500);
        }
        return latest;
    }
}
