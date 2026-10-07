package com.voltcanary.ingestion;

import com.voltcanary.common.events.device.SourceRuntimeConfigEvent;
import com.voltcanary.ingestion.connector.ConnectorRuntimeProperties;
import com.voltcanary.ingestion.source.SourceRegistryCache;
import com.voltcanary.ingestion.source.SourceRuntimeConfigStore;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

    @Autowired
    private SourceRegistryCache sourceRegistryCache;

    @Autowired
    private SourceRuntimeConfigStore sourceRuntimeConfigStore;

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

    @Test
    void bulkReadinessReportsEveryRequestedSource() {
        UUID unknown = UUID.fromString("00000000-0000-0000-0000-000000000002");
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/internal/ingestion/horizontal-scale-smoke/sources/readiness",
                Map.of("dataSourceIds", List.of(unknown)),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"requestedCount\":1");
        assertThat(response.getBody()).contains("\"knownSourceCount\":0");
        assertThat(response.getBody()).contains("\"knownRuntimeConfigCount\":0");
        assertThat(response.getBody()).contains("\"ready\":false");
        assertThat(response.getBody()).contains(unknown.toString());
    }

    @Test
    void bulkReadinessRejectsStaleTokenPrefix() {
        UUID dataSourceId = UUID.fromString("00000000-0000-0000-0000-000000000003");
        SourceRuntimeConfigEvent event = new SourceRuntimeConfigEvent(
                UUID.randomUUID(),
                "v1",
                "capacity-source-token-check",
                dataSourceId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "GENERIC_HTTP_PUSH",
                "REST",
                "HTTP",
                "ACTIVE",
                "v1",
                "v1",
                Map.of(),
                Map.of(),
                Map.of(),
                "current-prefix",
                "token-hash",
                "local",
                2L,
                Instant.now(),
                null,
                "test");
        sourceRegistryCache.apply(event);
        sourceRuntimeConfigStore.apply(event);

        ResponseEntity<String> stale = restTemplate.postForEntity(
                "/internal/ingestion/horizontal-scale-smoke/sources/readiness",
                Map.of(
                        "dataSourceIds", List.of(dataSourceId),
                        "expectedTokenPrefixes", Map.of(dataSourceId, "stale-prefix")),
                String.class);

        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(stale.getBody()).contains("\"ready\":false");
        assertThat(stale.getBody()).contains("\"matchingTokenPrefixCount\":0");
        assertThat(stale.getBody()).contains(dataSourceId.toString());

        ResponseEntity<String> current = restTemplate.postForEntity(
                "/internal/ingestion/horizontal-scale-smoke/sources/readiness",
                Map.of(
                        "dataSourceIds", List.of(dataSourceId),
                        "expectedTokenPrefixes", Map.of(dataSourceId, "current-prefix")),
                String.class);

        assertThat(current.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(current.getBody()).contains("\"ready\":true");
        assertThat(current.getBody()).contains("\"matchingTokenPrefixCount\":1");
    }
}
