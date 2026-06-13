package com.voltcanary.alert;

import com.voltcanary.alert.dto.AlertResponse;
import com.voltcanary.common.events.analytics.AnomalyDetectedEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
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

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.kafka.consume-enabled=true",
                "spring.kafka.consumer.group-id=alert-integration-test",
                "spring.jpa.hibernate.ddl-auto=none",
                "app.alert.authorization.enabled=false",
                "app.alert.notifications.worker-enabled=false",
                "app.alert.reconciliation.enabled=false",
                "app.alert.kafka.retry.max-attempts=2",
                "app.alert.kafka.retry.interval=100ms"
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

        assertThat(waitForAlertsContaining(deviceId))
                .anySatisfy(alert -> {
                    assertThat(alert.deviceId()).isEqualTo(deviceId);
                    assertThat(alert.alertType()).isEqualTo("POWER_SPIKE");
                    assertThat(alert.status()).isEqualTo("OPEN");
                });
    }

    @Test
    void failedAnomalyEventGoesToDlqAndConsumerContinues() throws Exception {
        publish(new AnomalyDetectedEvent(
                UUID.randomUUID(),
                null,
                UUID.randomUUID(),
                "POWER_SPIKE",
                "MEDIUM",
                30,
                "Invalid event without a device id.",
                Instant.now()));

        String dlqPayload = waitForDlqPayload("anomaly.detected.dlq.v1");
        assertThat(dlqPayload).contains("Invalid event without a device id.");

        UUID deviceId = UUID.randomUUID();
        publish(new AnomalyDetectedEvent(
                UUID.randomUUID(),
                deviceId,
                UUID.randomUUID(),
                "POWER_SPIKE",
                "MEDIUM",
                30,
                "A valid event after a failed one.",
                Instant.now()));

        assertThat(waitForAlertsContaining(deviceId))
                .anySatisfy(alert -> {
                    assertThat(alert.deviceId()).isEqualTo(deviceId);
                    assertThat(alert.alertType()).isEqualTo("POWER_SPIKE");
                    assertThat(alert.status()).isEqualTo("OPEN");
                });
    }

    private void publish(AnomalyDetectedEvent event) throws Exception {
        Properties properties = new Properties();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        try (KafkaProducer<String, AnomalyDetectedEvent> producer = new KafkaProducer<>(properties)) {
            String key = event.deviceId() == null ? event.eventId().toString() : event.deviceId().toString();
            producer.send(new ProducerRecord<>("anomaly.detected.v1", key, event)).get();
        }
    }

    private List<AlertResponse> waitForAlertsContaining(UUID deviceId) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        ResponseEntity<List<AlertResponse>> response = null;
        while (System.nanoTime() < deadline) {
            response = restTemplate.exchange("/alerts", HttpMethod.GET, null, new ParameterizedTypeReference<>() {
            });
            if (response.getStatusCode() == HttpStatus.OK
                    && response.getBody() != null
                    && response.getBody().stream().anyMatch(alert -> deviceId.equals(alert.deviceId()))) {
                return response.getBody();
            }
            Thread.sleep(500);
        }
        return response == null || response.getBody() == null ? List.of() : response.getBody();
    }

    private String waitForDlqPayload(String topic) {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "alert-dlq-test-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties)) {
            consumer.subscribe(Collections.singletonList(topic));
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (System.nanoTime() < deadline) {
                var records = consumer.poll(Duration.ofMillis(500));
                for (var record : records) {
                    if (record.value() != null) {
                        return record.value();
                    }
                }
            }
        }
        return "";
    }
}
