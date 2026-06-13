package com.voltcanary.ingestion;

import com.voltcanary.ingestion.device.DeviceValidationClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.redpanda.RedpandaContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.kafka.publish-enabled=true",
                "app.idempotency.redis-enabled=false",
                "app.ingestion.redis-rate-limit-enabled=false"
        })
class IngestionKafkaIntegrationTest {

    @Container
    static final RedpandaContainer kafka = new RedpandaContainer(DockerImageName.parse("redpandadata/redpanda:v24.2.18"));

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @MockitoBean
    private DeviceValidationClient deviceValidationClient;

    @Test
    void validReadingIsPublishedToKafka() {
        UUID deviceId = UUID.randomUUID();
        String sourceId = "plug-001";
        when(deviceValidationClient.dataSourceExists(deviceId, sourceId)).thenReturn(true);

        try (KafkaConsumer<String, String> consumer = consumer()) {
            consumer.subscribe(List.of("telemetry.raw.v1"));
            ResponseEntity<String> response = restTemplate.postForEntity(
                    "/ingestion/readings",
                    request(deviceId, sourceId),
                    String.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            String payload = pollValue(consumer);
            assertThat(payload).contains(deviceId.toString());
            assertThat(payload).contains(sourceId);
            assertThat(payload).contains("activePowerW");
        }
    }

    private KafkaConsumer<String, String> consumer() {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "ingestion-kafka-test-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        return new KafkaConsumer<>(properties);
    }

    private String pollValue(KafkaConsumer<String, String> consumer) {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            var records = consumer.poll(Duration.ofMillis(500));
            for (var record : records) {
                return record.value();
            }
        }
        throw new AssertionError("No telemetry.raw.v1 event consumed");
    }

    private Map<String, Object> request(UUID deviceId, String sourceId) {
        return Map.of(
                "deviceId", deviceId,
                "sourceId", sourceId,
                "timestamp", Instant.now().minusSeconds(30).toString(),
                "metrics", Map.of("activePowerW", 840.2, "voltageV", 230.1));
    }
}
