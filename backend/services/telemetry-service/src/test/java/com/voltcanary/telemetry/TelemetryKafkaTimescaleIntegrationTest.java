package com.voltcanary.telemetry;

import com.voltcanary.common.events.telemetry.TelemetryReceivedEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
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
                "app.telemetry.validation.max-past-age=30d",
                "spring.kafka.consumer.group-id=telemetry-integration-test",
                "spring.jpa.hibernate.ddl-auto=none"
        })
class TelemetryKafkaTimescaleIntegrationTest {

    @Container
    static final RedpandaContainer kafka = new RedpandaContainer(DockerImageName.parse("redpandadata/redpanda:v24.2.18"));

    @Container
    static final PostgreSQLContainer<?> timescale = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:latest-pg16").asCompatibleSubstituteFor("postgres"));

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

    @Test
    void consumesKafkaEventSavesTimescaleRowsAndReturnsLatestReading() throws Exception {
        UUID deviceId = UUID.randomUUID();
        Instant timestamp = Instant.parse("2026-05-12T12:30:00Z");
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
