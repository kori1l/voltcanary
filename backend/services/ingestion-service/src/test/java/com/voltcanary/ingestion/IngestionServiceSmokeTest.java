package com.voltcanary.ingestion;

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
                "spring.kafka.bootstrap-servers=localhost:19092"
        })
class IngestionServiceSmokeTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void contextLoads() {
    }

    @Test
    void healthEndpointReturns200() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void internalPingReturns200() {
        ResponseEntity<String> response = restTemplate.getForEntity("/internal/ping", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void horizontalScaleSmokeEndpointsAreDisabledByDefault() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/internal/ingestion/horizontal-scale-smoke/sources/00000000-0000-0000-0000-000000000001",
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
