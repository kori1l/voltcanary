package com.voltcanary.alert;

import com.voltcanary.alert.dto.AlertResponse;
import com.voltcanary.common.events.analytics.AnomalyDetectedEvent;
import java.time.Duration;
import java.time.Instant;
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
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.kafka.consume-enabled=true",
                "spring.kafka.consumer.group-id=alert-integration-test",
                "spring.jpa.hibernate.ddl-auto=none"
        })
class AlertPostgresKafkaIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static final RedpandaContainer kafka = new RedpandaContainer(DockerImageName.parse("redpandadata/redpanda:v24.2.18"));

    @DynamicPropertySource
    static void infrastructureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void consumesAnomalyEventAndPersistsAlertInPostgres() throws Exception {
        UUID deviceId = UUID.randomUUID();
        publish(new AnomalyDetectedEvent(
                UUID.randomUUID(),
                deviceId,
                UUID.randomUUID(),
                "POWER_SPIKE",
                "MEDIUM",
                30,
                "A power spike was detected.",
                Instant.now()));

        List<AlertResponse> alerts = waitForAlerts();

        assertThat(alerts).hasSize(1);
        assertThat(alerts.getFirst().deviceId()).isEqualTo(deviceId);
        assertThat(alerts.getFirst().alertType()).isEqualTo("POWER_SPIKE");
        assertThat(alerts.getFirst().status()).isEqualTo("OPEN");
    }

    private void publish(AnomalyDetectedEvent event) throws Exception {
        Properties properties = new Properties();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        try (KafkaProducer<String, AnomalyDetectedEvent> producer = new KafkaProducer<>(properties)) {
            producer.send(new ProducerRecord<>("anomaly.detected.v1", event.deviceId().toString(), event)).get();
        }
    }

    private List<AlertResponse> waitForAlerts() throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        ResponseEntity<List<AlertResponse>> response = null;
        while (System.nanoTime() < deadline) {
            response = restTemplate.exchange("/alerts", HttpMethod.GET, null, new ParameterizedTypeReference<>() {
            });
            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null && !response.getBody().isEmpty()) {
                return response.getBody();
            }
            Thread.sleep(500);
        }
        return response == null || response.getBody() == null ? List.of() : response.getBody();
    }
}
