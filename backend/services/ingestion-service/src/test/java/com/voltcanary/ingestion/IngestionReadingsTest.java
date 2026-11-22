package com.voltcanary.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.voltcanary.common.events.telemetry.TelemetryReceivedEvent;
import com.voltcanary.ingestion.kafka.TelemetryEventPublisher;
import com.voltcanary.ingestion.source.InternalDataSourceResponse;
import com.voltcanary.ingestion.source.SourceRegistryCache;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@AutoConfigureTestRestTemplate
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "app.kafka.publish-enabled=true",
        "app.idempotency.redis-enabled=false",
        "app.ingestion.redis-rate-limit-enabled=false",
        "app.ingestion.multi-replica-mode=false",
        "app.ingestion.local-correctness-state-enabled=true",
        "app.ingestion.backpressure-enabled=false",
        "spring.kafka.bootstrap-servers=localhost:19092",
    }
)
class IngestionReadingsTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @MockitoBean
    private TelemetryEventPublisher telemetryEventPublisher;

    @Autowired
    private SourceRegistryCache sourceRegistryCache;

    @MockitoBean
    private com.voltcanary.ingestion.source.SourceRegistryLookup registryLookup;

    @Test
    void authenticatedPositionsPreserveDistinctReadingsAtTheSameTime() {
        UUID device = UUID.randomUUID();
        UUID account = UUID.randomUUID();
        UUID binding = UUID.randomUUID();
        UUID snapshot = UUID.randomUUID();
        UUID epoch = UUID.randomUUID();
        String source = "positioned-" + UUID.randomUUID();
        String token = UUID.randomUUID().toString();
        String hash = com.voltcanary.vcl.VclCanonical.sha256(token);
        var pin = Map.of(
            "bindingId",
            binding,
            "accountId",
            account,
            "deviceId",
            device,
            "externalSourceId",
            source,
            "schemaRevision",
            1,
            "snapshotId",
            snapshot,
            "definitionDigest",
            "a".repeat(64)
        );
        sourceRegistryCache.put(
            new InternalDataSourceResponse(
                account,
                device,
                binding,
                source,
                "API",
                "REST",
                "ACTIVE",
                token.substring(0, 16),
                hash,
                Map.of("bindingSchemaPin", pin)
            ),
            System.nanoTime()
        );
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("deviceId", device);
        body.put("sourceId", source);
        body.put(
            "timestamp",
            Instant.now()
                .minusSeconds(2)
                .truncatedTo(java.time.temporal.ChronoUnit.MICROS)
                .toString()
        );
        body.put("metrics", Map.of("temperatureC", 22));
        body.put("sourcePosition", Map.of("epoch", epoch, "sequence", "1"));
        assertThat(
            restTemplate.postForEntity("/ingestion/readings", body, String.class).getStatusCode()
        ).isEqualTo(HttpStatus.BAD_REQUEST);
        var headers = new org.springframework.http.HttpHeaders();
        headers.setBearerAuth(token);
        assertThat(
            restTemplate
                .postForEntity(
                    "/ingestion/readings",
                    new org.springframework.http.HttpEntity<>(body, headers),
                    String.class
                )
                .getStatusCode()
        ).isEqualTo(HttpStatus.ACCEPTED);
        body.put("sourcePosition", Map.of("epoch", epoch, "sequence", "3"));
        assertThat(
            restTemplate
                .postForEntity(
                    "/ingestion/readings",
                    new org.springframework.http.HttpEntity<>(body, headers),
                    String.class
                )
                .getStatusCode()
        ).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(
            restTemplate
                .postForEntity(
                    "/ingestion/readings",
                    new org.springframework.http.HttpEntity<>(body, headers),
                    String.class
                )
                .getStatusCode()
        ).isEqualTo(HttpStatus.ACCEPTED);
        var captured = ArgumentCaptor.forClass(TelemetryReceivedEvent.class);
        verify(telemetryEventPublisher, times(2)).publish(captured.capture());
        assertThat(captured.getAllValues()).allMatch(event ->
            event.bindingSchemaPin().snapshotId().equals(snapshot)
        );
        assertThat(captured.getAllValues())
            .extracting(event -> event.sourcePosition().sequence())
            .containsExactly("1", "3");
    }

    @Test
    void configuredSourceFiltersBeforePublishingAndAcceptsTypedCustomValues() {
        UUID device = UUID.randomUUID();
        String source = "configured-" + UUID.randomUUID();
        var fields = java.util.List.of(
            Map.of(
                "path",
                "temperatureC",
                "key",
                "temperatureC",
                "label",
                "Temperature",
                "type",
                "number",
                "unit",
                "C",
                "store",
                false,
                "display",
                false
            ),
            Map.of(
                "path",
                "sensor.count",
                "key",
                "count",
                "label",
                "Count",
                "type",
                "number",
                "unit",
                "",
                "store",
                true,
                "display",
                true
            ),
            Map.of(
                "path",
                "sensor.open",
                "key",
                "open",
                "label",
                "Open",
                "type",
                "boolean",
                "unit",
                "",
                "store",
                true,
                "display",
                true
            )
        );
        sourceRegistryCache.put(
            new InternalDataSourceResponse(
                UUID.randomUUID(),
                device,
                UUID.randomUUID(),
                source,
                "API",
                "REST",
                "ACTIVE",
                null,
                null,
                Map.of("telemetryPolicy", Map.of("fields", fields))
            ),
            1
        );
        var body = Map.of(
            "deviceId",
            device,
            "sourceId",
            source,
            "timestamp",
            Instant.now().minusSeconds(2).toString(),
            "metrics",
            Map.of(
                "temperatureC",
                25,
                "sensor",
                Map.of("count", 0, "open", false, "ignored", "unselected"),
                "bindingSchemaPin",
                Map.of("bindingId", UUID.randomUUID().toString())
            )
        );
        assertThat(
            restTemplate.postForEntity("/ingestion/readings", body, String.class).getStatusCode()
        ).isEqualTo(HttpStatus.ACCEPTED);
        var captured = ArgumentCaptor.forClass(TelemetryReceivedEvent.class);
        verify(telemetryEventPublisher).publish(captured.capture());
        assertThat(captured.getValue().metrics()).containsOnlyKeys("configuredFields");
        assertThat(captured.getValue().bindingSchemaPin()).isNull();
        assertThat(
            com.voltcanary.common.events.telemetry.ConfiguredFields.values(
                captured.getValue().metrics()
            )
        ).containsOnlyKeys("count", "open");
        var invalid = Map.of(
            "deviceId",
            device,
            "sourceId",
            source,
            "timestamp",
            Instant.now().minusSeconds(1).toString(),
            "metrics",
            Map.of("sensor", Map.of("count", "not a number"))
        );
        assertThat(
            restTemplate.postForEntity("/ingestion/readings", invalid, String.class).getStatusCode()
        ).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(telemetryEventPublisher, times(1)).publish(any());
    }

    @Test
    void emptyConfiguredSelectionAcknowledgesWithoutPublishing() {
        UUID device = UUID.randomUUID();
        String source = "filtered-" + UUID.randomUUID();
        sourceRegistryCache.put(
            new InternalDataSourceResponse(
                UUID.randomUUID(),
                device,
                UUID.randomUUID(),
                source,
                "API",
                "REST",
                "ACTIVE",
                null,
                null,
                Map.of("telemetryPolicy", Map.of("fields", java.util.List.of()))
            ),
            1
        );
        var response = restTemplate.postForEntity(
            "/ingestion/readings",
            Map.of(
                "deviceId",
                device,
                "sourceId",
                source,
                "timestamp",
                Instant.now().minusSeconds(2).toString(),
                "metrics",
                Map.of("temperatureC", 25)
            ),
            String.class
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).contains("filtered");
        verify(telemetryEventPublisher, never()).publish(any());
    }

    @Test
    void acceptsValidReadingAndPublishesKafkaEvent() {
        UUID deviceId = UUID.randomUUID();
        String sourceId = "plug-001";
        Instant timestamp = recentTimestamp(30);
        seedSource(deviceId, sourceId, "ACTIVE");

        ResponseEntity<String> response = restTemplate.postForEntity(
            "/ingestion/readings",
            request(deviceId, sourceId, timestamp),
            String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        ArgumentCaptor<TelemetryReceivedEvent> eventCaptor = ArgumentCaptor.forClass(
            TelemetryReceivedEvent.class
        );
        verify(telemetryEventPublisher).publish(eventCaptor.capture());
        assertThat(eventCaptor.getValue().deviceId()).isEqualTo(deviceId);
        assertThat(eventCaptor.getValue().sourceId()).isEqualTo(sourceId);
        assertThat(eventCaptor.getValue().metrics()).containsKey("activePowerW");
    }

    @Test
    void rejectsUnknownDeviceDataSource() {
        UUID deviceId = UUID.randomUUID();
        String sourceId = "missing-source";

        ResponseEntity<String> response = restTemplate.postForEntity(
            "/ingestion/readings",
            request(deviceId, sourceId, recentTimestamp(31)),
            String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(telemetryEventPublisher, never()).publish(any());
    }

    @Test
    void duplicateReadingIsAcceptedButNotPublishedAgain() {
        UUID deviceId = UUID.randomUUID();
        String sourceId = "plug-001";
        Instant timestamp = recentTimestamp(32);
        seedSource(deviceId, sourceId, "ACTIVE");

        restTemplate.postForEntity(
            "/ingestion/readings",
            request(deviceId, sourceId, timestamp),
            String.class
        );
        ResponseEntity<String> duplicate = restTemplate.postForEntity(
            "/ingestion/readings",
            request(deviceId, sourceId, timestamp),
            String.class
        );

        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(duplicate.getBody()).contains("\"duplicateCount\":1");
        verify(telemetryEventPublisher, times(1)).publish(any());
    }

    @Test
    void rejectsUnsupportedMetricName() {
        UUID deviceId = UUID.randomUUID();
        String sourceId = "plug-001";
        seedSource(deviceId, sourceId, "ACTIVE");

        ResponseEntity<String> response = restTemplate.postForEntity(
            "/ingestion/readings",
            Map.of(
                "deviceId",
                deviceId,
                "sourceId",
                sourceId,
                "timestamp",
                recentTimestamp(33).toString(),
                "metrics",
                Map.of("unsupportedMetric", 24.5)
            ),
            String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(telemetryEventPublisher, never()).publish(any());
    }

    @Test
    void batchReturnsPartialSuccessWithPerItemStatus() {
        UUID deviceId = UUID.randomUUID();
        String sourceId = "plug-001";
        seedSource(deviceId, sourceId, "ACTIVE");

        ResponseEntity<String> response = restTemplate.postForEntity(
            "/ingestion/readings/batch",
            Map.of(
                "readings",
                java.util.List.of(
                    request(deviceId, sourceId, recentTimestamp(34)),
                    Map.of(
                        "deviceId",
                        deviceId,
                        "sourceId",
                        sourceId,
                        "timestamp",
                        recentTimestamp(35).toString(),
                        "metrics",
                        Map.of("unsupportedMetric", 1)
                    )
                )
            ),
            String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).contains("\"status\":\"partial\"");
        assertThat(response.getBody()).contains("\"acceptedCount\":1");
        assertThat(response.getBody()).contains("\"rejectedCount\":1");
        assertThat(response.getBody()).contains("\"index\":1");
        verify(telemetryEventPublisher, times(1)).publish(any());
    }

    @Test
    void publishFailureReturnsUnavailableAndDoesNotConsumeIdempotencyKey() {
        UUID deviceId = UUID.randomUUID();
        String sourceId = "plug-001";
        Instant timestamp = recentTimestamp(36);
        seedSource(deviceId, sourceId, "ACTIVE");
        doThrow(
            new org.springframework.web.server.ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Telemetry broker publish failed"
            )
        )
            .doNothing()
            .when(telemetryEventPublisher)
            .publish(any());

        ResponseEntity<String> failed = restTemplate.postForEntity(
            "/ingestion/readings",
            request(deviceId, sourceId, timestamp),
            String.class
        );
        ResponseEntity<String> retried = restTemplate.postForEntity(
            "/ingestion/readings",
            request(deviceId, sourceId, timestamp),
            String.class
        );

        assertThat(failed.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(retried.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        var published = ArgumentCaptor.forClass(TelemetryReceivedEvent.class);
        verify(telemetryEventPublisher, times(2)).publish(published.capture());
        assertThat(published.getAllValues().get(1)).isEqualTo(published.getAllValues().get(0));
    }

    private Map<String, Object> request(UUID deviceId, String sourceId, Instant timestamp) {
        return Map.of(
            "deviceId",
            deviceId,
            "sourceId",
            sourceId,
            "timestamp",
            timestamp.toString(),
            "metrics",
            Map.of(
                "activePowerW",
                840.2,
                "voltageV",
                230.1,
                "currentA",
                3.65,
                "energyWh",
                12450.7,
                "rssi",
                -61,
                "relayState",
                "ON"
            )
        );
    }

    @Test
    void acceptsTransportContextAndRejectsInvalidFieldsBeforePublishing() {
        UUID deviceId = UUID.randomUUID();
        String sourceId = "hsl-context";
        seedSource(deviceId, sourceId, "ACTIVE");
        var context = Map.<String, Object>of(
            "routeShortName",
            "3",
            "doorsOpen",
            false,
            "scheduleDeviationSeconds",
            0
        );
        var body = Map.of(
            "deviceId",
            deviceId,
            "sourceId",
            sourceId,
            "timestamp",
            recentTimestamp(10).toString(),
            "metrics",
            Map.of("latitude", 60.18, "longitude", 24.92, "transport", context)
        );
        assertThat(
            restTemplate.postForEntity("/ingestion/readings", body, String.class).getStatusCode()
        ).isEqualTo(HttpStatus.ACCEPTED);
        var captor = ArgumentCaptor.forClass(TelemetryReceivedEvent.class);
        verify(telemetryEventPublisher).publish(captor.capture());
        assertThat(captor.getValue().metrics()).containsEntry("transport", context);
        var invalid = Map.of(
            "deviceId",
            deviceId,
            "sourceId",
            sourceId,
            "timestamp",
            recentTimestamp(5).toString(),
            "metrics",
            Map.of("latitude", 60.18, "longitude", 24.92, "transport", Map.of("doorsOpen", 0))
        );
        assertThat(
            restTemplate.postForEntity("/ingestion/readings", invalid, String.class).getStatusCode()
        ).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(telemetryEventPublisher, times(1)).publish(any());
    }

    private Instant recentTimestamp(long secondsAgo) {
        return Instant.now().minusSeconds(secondsAgo);
    }

    @Test
    void publishedSchemaPinComesFromResolvedSourceInsteadOfIncomingMetrics() {
        UUID device = UUID.randomUUID(),
            account = UUID.randomUUID(),
            source = UUID.randomUUID(),
            snapshot = UUID.randomUUID();
        String external = "schema-pin-" + source;
        var pin = Map.<String, Object>of(
            "bindingId",
            source.toString(),
            "accountId",
            account.toString(),
            "deviceId",
            device.toString(),
            "externalSourceId",
            external,
            "schemaRevision",
            1,
            "snapshotId",
            snapshot.toString(),
            "definitionDigest",
            "a".repeat(64)
        );
        sourceRegistryCache.put(
            new InternalDataSourceResponse(
                account,
                device,
                source,
                external,
                "SENSOR",
                "REST",
                "ACTIVE",
                null,
                null,
                Map.of(
                    "bindingSchemaPin",
                    pin,
                    "telemetryPolicy",
                    Map.of(
                        "fields",
                        java.util.List.of(
                            Map.of(
                                "key",
                                "temperatureC",
                                "path",
                                "temperatureC",
                                "type",
                                "number",
                                "unit",
                                "C",
                                "label",
                                "Temperature",
                                "store",
                                true,
                                "display",
                                true
                            )
                        )
                    )
                )
            ),
            1
        );
        var body = Map.of(
            "deviceId",
            device,
            "sourceId",
            external,
            "timestamp",
            recentTimestamp(2).toString(),
            "metrics",
            Map.of(
                "temperatureC",
                23,
                "bindingSchemaPin",
                Map.of("bindingId", UUID.randomUUID().toString())
            )
        );
        assertThat(
            restTemplate.postForEntity("/ingestion/readings", body, String.class).getStatusCode()
        ).isEqualTo(HttpStatus.ACCEPTED);
        var captured = ArgumentCaptor.forClass(TelemetryReceivedEvent.class);
        verify(telemetryEventPublisher).publish(captured.capture());
        assertThat(captured.getValue().bindingSchemaPin().snapshotId()).isEqualTo(snapshot);
        assertThat(captured.getValue().bindingSchemaPin().bindingId()).isEqualTo(source);
        assertThat(captured.getValue().accountId()).isEqualTo(account);
    }

    @Test
    void foreignOwnerSchemaPinFailsBeforePublishingAcceptedTelemetry() {
        UUID device = UUID.randomUUID(),
            account = UUID.randomUUID(),
            source = UUID.randomUUID();
        String external = "foreign-schema-" + source;
        var pin = Map.<String, Object>of(
            "bindingId",
            source.toString(),
            "accountId",
            UUID.randomUUID().toString(),
            "deviceId",
            device.toString(),
            "externalSourceId",
            external,
            "schemaRevision",
            1,
            "snapshotId",
            UUID.randomUUID().toString(),
            "definitionDigest",
            "a".repeat(64)
        );
        sourceRegistryCache.put(
            new InternalDataSourceResponse(
                account,
                device,
                source,
                external,
                "SENSOR",
                "REST",
                "ACTIVE",
                null,
                null,
                Map.of("bindingSchemaPin", pin)
            ),
            1
        );
        var body = Map.of(
            "deviceId",
            device,
            "sourceId",
            external,
            "timestamp",
            recentTimestamp(2).toString(),
            "metrics",
            Map.of("temperatureC", 23)
        );
        assertThat(
            restTemplate.postForEntity("/ingestion/readings", body, String.class).getStatusCode()
        ).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        verify(telemetryEventPublisher, never()).publish(any());
    }

    private void seedSource(UUID deviceId, String sourceId, String status) {
        sourceRegistryCache.put(
            new InternalDataSourceResponse(
                UUID.randomUUID(),
                deviceId,
                UUID.randomUUID(),
                sourceId,
                "GENERIC_HTTP_PUSH",
                "REST",
                status,
                null,
                null
            ),
            System.nanoTime()
        );
    }
}
