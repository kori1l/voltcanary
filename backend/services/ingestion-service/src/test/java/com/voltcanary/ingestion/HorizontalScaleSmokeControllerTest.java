package com.voltcanary.ingestion;

import com.voltcanary.ingestion.connector.ConnectorRuntimeProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.kafka.publish-enabled=false",
                "app.idempotency.redis-enabled=false",
                "app.ingestion.redis-rate-limit-enabled=false",
                "app.ingestion.multi-replica-mode=false",
                "app.ingestion.local-correctness-state-enabled=true",
                "app.ingestion.horizontal-scale-smoke.enabled=true",
                "app.ingestion.connector-runtime.instance-id=test-replica-a",
                "spring.kafka.bootstrap-servers=localhost:19092"
        })
class HorizontalScaleSmokeControllerTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ConnectorRuntimeProperties properties;

    @Autowired
    private String sourceRuntimeConfigConsumerGroupId;

    @Test
    void sourceDiagnosticsExposeReplicaSpecificState() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/internal/ingestion/horizontal-scale-smoke/sources/00000000-0000-0000-0000-000000000001",
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"instanceId\":\"test-replica-a\"");
        assertThat(response.getBody()).contains("\"knownRuntimeConfig\":false");
    }

    @Test
    void sourceRuntimeConfigConsumerGroupIsReplicaSpecific() {
        assertThat(properties.getInstanceId()).isEqualTo("test-replica-a");
        assertThat(sourceRuntimeConfigConsumerGroupId)
                .isEqualTo("ingestion-service-source-runtime-config-test-replica-a");
    }
}
