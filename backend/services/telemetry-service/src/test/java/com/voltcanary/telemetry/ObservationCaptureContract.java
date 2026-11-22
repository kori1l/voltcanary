package com.voltcanary.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.common.events.device.SourceBindingSchemaEvent;
import com.voltcanary.common.events.telemetry.SourceBindingSchemaPin;
import com.voltcanary.common.events.telemetry.TelemetryReceivedEvent;
import com.voltcanary.telemetry.observation.ObservationCapture;
import com.voltcanary.telemetry.observation.ObservationCaptureProperties;
import com.voltcanary.telemetry.observation.ObservationCaptureUnavailable;
import com.voltcanary.telemetry.observation.ObservationReconciliation;
import com.voltcanary.telemetry.observation.ObservationSchemaConsumer;
import com.voltcanary.telemetry.observation.ObservationSchemas;
import com.voltcanary.telemetry.service.DeviceHistoryDeletion;
import com.voltcanary.telemetry.service.TelemetryEventProcessor;
import com.voltcanary.telemetry.variable.ConfiguredTelemetryHandler;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclSourceBindingProfile;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "app.kafka.consume-enabled=false",
        "app.kafka.publish-enabled=false",
        "app.observations.consume-enabled=false",
        "app.observations.enabled=true",
        "app.telemetry.authorization.enabled=false",
        "app.telemetry.validation.redis-rate-limit-enabled=false",
        "app.telemetry.reconciliation.enabled=false",
        "management.health.redis.enabled=false",
        "app.internal.coverage-attestor-token=coverage-attestor-test",
    }
)
abstract class ObservationCaptureContract {

    @Autowired
    ObservationCapture capture;

    @Autowired
    ObservationSchemas schemas;

    @Autowired
    ObservationSchemaConsumer consumer;

    @Autowired
    ObservationCaptureProperties properties;

    @Autowired
    ConfiguredTelemetryHandler handler;

    @Autowired
    DeviceHistoryDeletion deletion;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    com.voltcanary.telemetry.observation.ObservationGenerations generations;

    @Autowired
    com.voltcanary.telemetry.observation.ObservationReads observationReads;

    @Autowired
    com.voltcanary.telemetry.observation.ObservationReadiness readiness;

    @Autowired
    com.voltcanary.telemetry.observation.SourcePositionProgress progress;

    @Autowired
    com.voltcanary.telemetry.observation.SourceCoverageAttestations coverage;

    @Autowired
    org.springframework.transaction.PlatformTransactionManager transactions;

    @Autowired
    org.springframework.boot.resttestclient.TestRestTemplate http;

    @MockitoBean
    ObservationReconciliation reconciliation;

    @MockitoSpyBean
    TelemetryEventProcessor legacy;

    record Fixture(SourceBindingSchemaEvent schema, UUID booleanField, UUID textField) {}

    @AfterEach
    void reset() {
        properties.setEnabled(true);
        properties.setBindingIds(Set.of());
        properties.setPendingLimit(10000);
        properties.setObservationLimit(100000);
    }

    @Test
    void producerProgressRetainsGapAndRecoversAcrossRebuildWithoutClaimingCoverage() {
        var fixture = fixture();
        schemas.apply(fixture.schema());
        var binding = fixture.schema().pin();
        var pin = new com.voltcanary.vcl.VclObservationReadiness.Pin(
            binding.bindingId(),
            binding.accountId(),
            binding.deviceId(),
            binding.externalSourceId(),
            binding.schemaRevision(),
            binding.snapshotId(),
            binding.definitionDigest()
        );
        UUID epoch = UUID.randomUUID();
        var first = positioned(fixture, epoch, "1");
        var third = positioned(fixture, epoch, "3");
        assertEquals(ObservationCapture.Outcome.ACCEPTED, capture.capture(first));
        assertEquals(ObservationCapture.Outcome.ACCEPTED, capture.capture(third));
        var incomplete = progress.read(pin, epoch);
        assertEquals("1", incomplete.contiguousThrough());
        assertEquals("3", incomplete.highestObserved());
        assertTrue(incomplete.gapPresent());
        assertEquals("UNKNOWN", incomplete.upstreamCoverage());
        assertEquals(ObservationCapture.Outcome.REPLAY, capture.capture(third));
        assertEquals(
            "SOURCE_POSITION_CONFLICT",
            assertThrows(ObservationCaptureUnavailable.class, () ->
                capture.capture(positioned(fixture, epoch, "3"))
            ).getMessage()
        );
        assertEquals(2, count(fixture));
        assertEquals(incomplete, progress.read(pin, epoch));
        assertEquals(
            ObservationCapture.Outcome.ACCEPTED,
            capture.capture(positioned(fixture, epoch, "2"))
        );
        var rebuilt = new com.voltcanary.telemetry.observation.SourcePositionProgress(
            jdbc,
            readiness
        );
        var completed = rebuilt.read(pin, epoch);
        assertEquals("3", completed.contiguousThrough());
        assertFalse(completed.gapPresent());
        assertEquals("UNKNOWN", completed.upstreamCoverage());
        assertEquals("0", progress.read(pin, UUID.randomUUID()).contiguousThrough());
    }

    @Test
    void explicitCoverageProofRequiresClosedGapAndFailsOnLateContradiction() {
        var fixture = fixture();
        schemas.apply(fixture.schema());
        var binding = fixture.schema().pin();
        var pin = new com.voltcanary.vcl.VclObservationReadiness.Pin(
            binding.bindingId(),
            binding.accountId(),
            binding.deviceId(),
            binding.externalSourceId(),
            binding.schemaRevision(),
            binding.snapshotId(),
            binding.definitionDigest()
        );
        UUID epoch = UUID.randomUUID();
        var first = positioned(fixture, epoch, "1");
        capture.capture(first);
        capture.capture(positioned(fixture, epoch, "3"));
        UUID generation = progress.read(pin, epoch).inputGeneration();
        UUID proofId = UUID.randomUUID();
        var proof = new com.voltcanary.telemetry.observation.SourceCoverageAttestations.Proof(
            proofId,
            generation,
            new com.voltcanary.common.events.telemetry.SourcePosition(epoch, "3"),
            first.timestamp().minusSeconds(1),
            first.timestamp().plusSeconds(1)
        );
        var request = mapper
            .createObjectNode()
            .put("proofId", proofId.toString())
            .put("inputGeneration", generation.toString())
            .put("producerEpoch", epoch.toString())
            .put("throughSequence", "3")
            .put("from", proof.from().toString())
            .put("until", proof.until().toString());
        request.set("pin", mapper.valueToTree(binding));
        String path = "/internal/observations/source-coverage";
        var headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        headers.set("X-Coverage-Attestor-Token", "dev-internal-token");
        assertEquals(
            org.springframework.http.HttpStatus.UNAUTHORIZED,
            http
                .postForEntity(
                    path,
                    new org.springframework.http.HttpEntity<>(request.toString(), headers),
                    JsonNode.class
                )
                .getStatusCode()
        );
        headers.set("X-Coverage-Attestor-Token", "coverage-attestor-test");
        var entity = new org.springframework.http.HttpEntity<>(request.toString(), headers);
        assertEquals(
            "SOURCE_GAP",
            http.postForEntity(path, entity, JsonNode.class).getBody().path("reason").textValue()
        );
        assertEquals("SOURCE_GAP", coverage.read(pin, proofId).reason());
        var pendingConflict =
            new com.voltcanary.telemetry.observation.SourceCoverageAttestations.Proof(
                proofId,
                generation,
                proof.position(),
                proof.from().minusSeconds(1),
                proof.until()
            );
        assertEquals(
            "SOURCE_COVERAGE_CONFLICT",
            assertThrows(ObservationCaptureUnavailable.class, () ->
                coverage.attest(pin, pendingConflict)
            ).getMessage()
        );
        var wrongGeneration =
            new com.voltcanary.telemetry.observation.SourceCoverageAttestations.Proof(
                UUID.randomUUID(),
                UUID.randomUUID(),
                proof.position(),
                proof.from(),
                proof.until()
            );
        assertEquals("GENERATION_CHANGED", coverage.attest(pin, wrongGeneration).reason());
        assertEquals("PROOF_UNAVAILABLE", coverage.read(pin, wrongGeneration.proofId()).reason());
        capture.capture(positioned(fixture, epoch, "2"));
        var recovered = new com.voltcanary.telemetry.observation.SourceCoverageAttestations(
            jdbc,
            new com.voltcanary.telemetry.observation.SourcePositionProgress(jdbc, readiness)
        );
        assertEquals("COVERED", recovered.read(pin, proofId).coverage());
        var sealed = http.postForEntity(path, entity, JsonNode.class);
        assertEquals(
            org.springframework.http.HttpStatus.OK,
            sealed.getStatusCode(),
            sealed.getBody().toString()
        );
        assertEquals("COVERED", sealed.getBody().path("coverage").textValue());
        var decoded = com.voltcanary.vcl.VclSourceCoverage.parse(
            sealed.getBody().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );
        assertEquals("3", decoded.requiredCheckpoint());
        assertEquals(proof.until(), decoded.evidence().until());
        assertEquals(sealed.getBody(), http.postForEntity(path, entity, JsonNode.class).getBody());
        assertEquals(proof, coverage.read(pin, proofId).evidence());
        var conflict = new com.voltcanary.telemetry.observation.SourceCoverageAttestations.Proof(
            proofId,
            generation,
            proof.position(),
            proof.from().minusSeconds(1),
            proof.until()
        );
        assertEquals(
            "SOURCE_COVERAGE_CONFLICT",
            assertThrows(ObservationCaptureUnavailable.class, () ->
                coverage.attest(pin, conflict)
            ).getMessage()
        );
        var late = positioned(fixture, epoch, "4");
        capture.capture(
            new TelemetryReceivedEvent(
                late.eventId(),
                late.deviceId(),
                late.sourceId(),
                first.timestamp(),
                late.metrics(),
                late.receivedAt(),
                late.eventVersion(),
                late.correlationId(),
                late.producer(),
                late.accountId(),
                first.timestamp(),
                late.processingContext(),
                late.bindingSchemaPin(),
                late.sourcePosition()
            )
        );
        assertEquals("CONTRADICTORY_OBSERVATION", coverage.read(pin, proofId).reason());
        assertEquals("UNKNOWN", progress.read(pin, epoch).upstreamCoverage());
    }

    @Test
    void coverageSearchCanRequireANewerJournalCut() {
        var fixture = fixture();
        schemas.apply(fixture.schema());
        var binding = fixture.schema().pin();
        var pin = new com.voltcanary.vcl.VclObservationReadiness.Pin(
            binding.bindingId(),
            binding.accountId(),
            binding.deviceId(),
            binding.externalSourceId(),
            binding.schemaRevision(),
            binding.snapshotId(),
            binding.definitionDigest()
        );
        var epoch = UUID.randomUUID();
        var first = positioned(fixture, epoch, "1");
        capture.capture(first);
        var generation = progress.read(pin, epoch).inputGeneration();
        var from = first.timestamp().minusSeconds(1);
        var until = first.timestamp().plusSeconds(1);
        var basis = com.voltcanary.vcl.VclSourceCoverage.SOURCE_EVENT_TIME;
        var older = new com.voltcanary.telemetry.observation.SourceCoverageAttestations.Proof(
            UUID.randomUUID(),
            generation,
            first.sourcePosition(),
            from,
            until.plusMillis(200),
            basis
        );
        assertEquals("COVERED", coverage.attest(pin, older).coverage());
        var second = positioned(fixture, epoch, "2");
        var at = until.plusMillis(400);
        capture.capture(
            new TelemetryReceivedEvent(
                second.eventId(),
                second.deviceId(),
                second.sourceId(),
                at,
                second.metrics(),
                at.plusSeconds(1),
                second.eventVersion(),
                second.correlationId(),
                second.producer(),
                second.accountId(),
                at,
                second.processingContext(),
                second.bindingSchemaPin(),
                second.sourcePosition()
            )
        );
        var newer = new com.voltcanary.telemetry.observation.SourceCoverageAttestations.Proof(
            UUID.randomUUID(),
            generation,
            second.sourcePosition(),
            from,
            until,
            basis
        );
        assertEquals("COVERED", coverage.attest(pin, newer).coverage());
        assertEquals(
            older.proofId(),
            coverage.findQualified(
                new com.voltcanary.vcl.VclCoverageSearch(pin, generation, from, until, 0)
            )
        );
        assertEquals(
            newer.proofId(),
            coverage.findQualified(
                new com.voltcanary.vcl.VclCoverageSearch(pin, generation, from, until, 2)
            )
        );
        assertNull(
            coverage.findQualified(
                new com.voltcanary.vcl.VclCoverageSearch(pin, generation, from, until, 3)
            )
        );
    }

    private TelemetryReceivedEvent positioned(Fixture fixture, UUID epoch, String sequence) {
        var event = event(fixture, UUID.randomUUID(), false, "state");
        return new TelemetryReceivedEvent(
            event.eventId(),
            event.deviceId(),
            event.sourceId(),
            event.timestamp(),
            event.metrics(),
            event.receivedAt(),
            event.eventVersion(),
            event.correlationId(),
            event.producer(),
            event.accountId(),
            event.occurredAt(),
            event.processingContext(),
            event.bindingSchemaPin(),
            new com.voltcanary.common.events.telemetry.SourcePosition(epoch, sequence)
        );
    }

    @Test
    void envelopeAndExactTypedValuesCommitWithCheckpointAndOutboxWithoutReadinessClaims() throws Exception {
        var fixture = fixture();
        consumer.consume(mapper.writeValueAsString(fixture.schema().envelope()));
        var event = event(fixture, UUID.randomUUID(), false, "");
        assertEquals(ObservationCapture.Outcome.ACCEPTED, capture.capture(event));
        var value = observation(event);
        assertEquals("KNOWN", value.at("/fields/0/knowledge").asText());
        assertFalse(value.at("/fields/0/value").asBoolean());
        assertEquals("", value.at("/fields/1/value").asText());
        assertEquals("UNKNOWN", value.path("upstreamCoverage").asText());
        assertEquals("accepted-boolean-text-2", value.path("profile").asText());
        assertEquals(
            generations.find(fixture.schema().pin().bindingId()).orElseThrow().id().toString(),
            value.path("inputGeneration").asText()
        );
        assertFalse(value.path("negativeEvidenceReady").asBoolean());
        assertFalse(value.path("ruleExecutionReady").asBoolean());
        var wire = mapper.readTree(
            jdbc.queryForObject(
                "SELECT payload_json FROM telemetry_event_outbox WHERE event_key=?",
                String.class,
                "observations:" + event.eventId()
            )
        );
        assertEquals(event.eventId().toString(), wire.path("eventId").asText());
        assertEquals(event.accountId().toString(), wire.path("accountId").asText());
        assertEquals(value, wire.path("payload"));
    }

    @Test
    void independentEventsAtTheSameTimestampSurviveLatestMeasurementCoalescing() throws Exception {
        var fixture = fixture();
        schemas.apply(fixture.schema());
        var first = event(fixture, UUID.randomUUID(), false, "stopped");
        var second = new TelemetryReceivedEvent(
            UUID.randomUUID(),
            first.deviceId(),
            first.sourceId(),
            first.timestamp(),
            event(fixture, UUID.randomUUID(), true, "moving").metrics(),
            first.receivedAt(),
            "v1",
            null,
            "ingestion-service",
            first.accountId(),
            first.timestamp(),
            null,
            first.bindingSchemaPin()
        );
        handler.process(first);
        handler.process(second);
        assertEquals(2, count(fixture));
        assertEquals("1", observation(first).path("checkpoint").asText());
        assertEquals("2", observation(second).path("checkpoint").asText());
    }

    @Test
    void replayReturnsTheOriginalFactAndChangedPayloadCannotReuseItsEventIdentity() throws Exception {
        var fixture = fixture();
        schemas.apply(fixture.schema());
        var event = event(fixture, UUID.randomUUID(), false, "original");
        capture.capture(event);
        String stored = observation(event).toString();
        assertEquals(ObservationCapture.Outcome.REPLAY, capture.capture(event));
        assertEquals(stored, observation(event).toString());
        var changed = new TelemetryReceivedEvent(
            event.eventId(),
            event.deviceId(),
            event.sourceId(),
            event.timestamp(),
            event(fixture, UUID.randomUUID(), true, "changed").metrics(),
            event.receivedAt(),
            "v1",
            null,
            "ingestion-service",
            event.accountId(),
            event.timestamp(),
            null,
            event.bindingSchemaPin()
        );
        assertEquals(
            "OBSERVATION_EVENT_CONFLICT",
            assertThrows(ObservationCaptureUnavailable.class, () ->
                capture.capture(changed)
            ).getMessage()
        );
        assertEquals(1, count(fixture));
    }

    @Test
    void missingSchemaIsDurablyParkedAndOnlyItsExactRevisionCanResolveIt() {
        var fixture = fixture();
        var event = event(fixture, UUID.randomUUID(), true, null);
        assertEquals(ObservationCapture.Outcome.PENDING, capture.capture(event));
        assertTrue(generations.find(fixture.schema().pin().bindingId()).isEmpty());
        assertEquals(ObservationCapture.Outcome.PENDING, capture.capture(event));
        var wrong = fixture(
            2,
            fixture.schema().pin().bindingId(),
            fixture.schema().pin().accountId(),
            fixture.schema().pin().deviceId()
        );
        schemas.apply(wrong.schema());
        assertEquals(ObservationCapture.Outcome.PENDING, capture.retry(event.eventId()));
        schemas.apply(fixture.schema());
        assertEquals(ObservationCapture.Outcome.ACCEPTED, capture.retry(event.eventId()));
        assertEquals(ObservationCapture.Outcome.REPLAY, capture.retry(event.eventId()));
        assertNull(
            jdbc.queryForObject(
                "SELECT payload_json FROM cfg_observation_input WHERE event_id=?",
                String.class,
                event.eventId()
            )
        );
    }

    @Test
    void unavailableTextIsUnknownAndNeverAnInventedEmptyValue() throws Exception {
        var fixture = fixture();
        schemas.apply(fixture.schema());
        var event = event(fixture, UUID.randomUUID(), false, null);
        capture.capture(event);
        assertEquals("UNKNOWN", observation(event).at("/fields/1/knowledge").asText());
        assertEquals("MISSING_OR_NULL_INPUT", observation(event).at("/fields/1/reason").asText());
        assertEquals(true, observation(event).at("/fields/1/value").isNull());
    }

    @Test
    void schemaIdentityConflictAndForeignScopeCannotBeSilentlyRetargeted() {
        var fixture = fixture();
        schemas.apply(fixture.schema());
        var pin = fixture.schema().pin();
        var altered = new SourceBindingSchemaPin(
            pin.bindingId(),
            pin.accountId(),
            pin.deviceId(),
            pin.externalSourceId(),
            pin.schemaRevision(),
            UUID.randomUUID(),
            pin.definitionDigest()
        );
        assertThrows(ObservationCaptureUnavailable.class, () ->
            schemas.apply(
                new SourceBindingSchemaEvent(
                    UUID.randomUUID(),
                    "v1",
                    altered,
                    fixture.schema().canonicalDefinition(),
                    true,
                    Instant.now(),
                    "device-service"
                )
            )
        );
        var event = event(fixture, UUID.randomUUID(), true, "x");
        var foreign = new TelemetryReceivedEvent(
            event.eventId(),
            event.deviceId(),
            event.sourceId(),
            event.timestamp(),
            event.metrics(),
            event.receivedAt(),
            "v1",
            null,
            "ingestion-service",
            UUID.randomUUID(),
            event.timestamp(),
            null,
            pin
        );
        assertEquals(
            "OBSERVATION_INPUT_INVALID",
            assertThrows(ObservationCaptureUnavailable.class, () ->
                capture.capture(foreign)
            ).getMessage()
        );
        assertEquals(0, count(fixture));
    }

    @Test
    void optInIsExplicitAndLegacyEventsDoNotAcquireSyntheticPins() {
        var fixture = fixture();
        var event = event(fixture, UUID.randomUUID(), true, "x");
        properties.setEnabled(false);
        assertEquals(ObservationCapture.Outcome.SKIPPED, capture.capture(event));
        properties.setEnabled(true);
        properties.setBindingIds(Set.of());
        assertEquals(ObservationCapture.Outcome.SKIPPED, capture.capture(event));
        var legacyEvent = new TelemetryReceivedEvent(
            event.eventId(),
            event.deviceId(),
            event.sourceId(),
            event.timestamp(),
            event.metrics(),
            event.receivedAt()
        );
        assertEquals(ObservationCapture.Outcome.SKIPPED, capture.capture(legacyEvent));
        assertEquals(0, count(fixture));
    }

    @Test
    void outboxFailureRollsBackFactInputReceiptAndCheckpoint() {
        var fixture = fixture();
        schemas.apply(fixture.schema());
        var event = event(fixture, UUID.randomUUID(), true, "x");
        String constraint = "reject_observation_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute(
            "ALTER TABLE telemetry_event_outbox ADD CONSTRAINT " +
                constraint +
                " CHECK(event_key<>'observations:" +
                event.eventId() +
                "')"
        );
        try {
            assertThrows(ObservationCaptureUnavailable.class, () -> capture.capture(event));
            assertEquals(0, count(fixture));
            assertEquals(
                0,
                jdbc.queryForObject(
                    "SELECT COUNT(*) FROM cfg_observation_input WHERE event_id=?",
                    Integer.class,
                    event.eventId()
                )
            );
            assertEquals(
                0L,
                jdbc.queryForObject(
                    "SELECT head_checkpoint FROM cfg_observation_stream WHERE binding_id=?",
                    Long.class,
                    fixture.schema().pin().bindingId()
                )
            );
            assertTrue(generations.find(fixture.schema().pin().bindingId()).isEmpty());
        } finally {
            jdbc.execute("ALTER TABLE telemetry_event_outbox DROP CONSTRAINT " + constraint);
        }
        assertEquals(ObservationCapture.Outcome.ACCEPTED, capture.capture(event));
    }

    @Test
    void laterLegacyFailureRollsBackObservationInTheOwningRawTransaction() {
        var fixture = fixture();
        schemas.apply(fixture.schema());
        var event = event(fixture, UUID.randomUUID(), true, "x");
        doThrow(new IllegalStateException("downstream fixture")).when(legacy).process(event);
        assertThrows(IllegalStateException.class, () -> handler.process(event));
        assertEquals(0, count(fixture));
        assertEquals(
            0,
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM cfg_observation_input WHERE event_id=?",
                Integer.class,
                event.eventId()
            )
        );
    }

    @Test
    void concurrentIdenticalDeliveryHasOneFactOneCheckpointAndOneReceipt() throws Exception {
        var fixture = fixture();
        schemas.apply(fixture.schema());
        var event = event(fixture, UUID.randomUUID(), true, "x");
        var first = CompletableFuture.supplyAsync(() -> capture.capture(event));
        var second = CompletableFuture.supplyAsync(() -> capture.capture(event));
        var outcomes = Set.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        assertEquals(
            Set.of(ObservationCapture.Outcome.ACCEPTED, ObservationCapture.Outcome.REPLAY),
            outcomes
        );
        assertEquals(1, count(fixture));
        assertEquals(
            1,
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM telemetry_event_outbox WHERE event_key=?",
                Integer.class,
                "observations:" + event.eventId()
            )
        );
    }

    @Test
    void quotasHoldInputsForRecoveryInsteadOfClaimingCompleteCapture() {
        var fixture = fixture();
        properties.setPendingLimit(1);
        var first = event(fixture, UUID.randomUUID(), true, "x");
        assertEquals(ObservationCapture.Outcome.PENDING, capture.capture(first));
        assertEquals(
            "OBSERVATION_PENDING_QUOTA",
            assertThrows(ObservationCaptureUnavailable.class, () ->
                capture.capture(event(fixture, UUID.randomUUID(), false, "x"))
            ).getMessage()
        );
        schemas.apply(fixture.schema());
        capture.retry(first.eventId());
        properties.setObservationLimit(1);
        assertEquals(
            "OBSERVATION_CAPACITY_REACHED",
            assertThrows(ObservationCaptureUnavailable.class, () ->
                capture.capture(event(fixture, UUID.randomUUID(), false, "x"))
            ).getMessage()
        );
        assertEquals(1, count(fixture));
    }

    @Test
    void deletionPurgesJournalPendingSchemasAndOutboxWithoutResurrection() {
        var fixture = fixture();
        schemas.apply(fixture.schema());
        var event = event(fixture, UUID.randomUUID(), true, "x");
        capture.capture(event);
        deletion.close(event.deviceId(), true);
        assertEquals(0, count(fixture));
        assertEquals(
            0,
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM cfg_observation_input WHERE device_id=?",
                Integer.class,
                event.deviceId()
            )
        );
        assertEquals(
            0,
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM cfg_observation_stream WHERE device_id=?",
                Integer.class,
                event.deviceId()
            )
        );
        schemas.apply(fixture.schema());
        assertEquals(ObservationCapture.Outcome.DELETED, capture.capture(event));
        assertEquals(
            0,
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM telemetry_event_outbox WHERE event_key=?",
                Integer.class,
                "observations:" + event.eventId()
            )
        );
    }

    @Test
    void legacyUnsafeNumbersAreHashedAsWireTextWithoutCanonicalNumericAliasing() {
        var fixture = fixture();
        schemas.apply(fixture.schema());
        var event = event(fixture, UUID.randomUUID(), true, "x");
        var original = new java.util.LinkedHashMap<>(event.metrics());
        original.put("counter", 9007199254740992L);
        var changed = new java.util.LinkedHashMap<>(event.metrics());
        changed.put("counter", 9007199254740993L);
        var first = new TelemetryReceivedEvent(
            event.eventId(),
            event.deviceId(),
            event.sourceId(),
            event.timestamp(),
            original,
            event.receivedAt(),
            "v1",
            null,
            "ingestion-service",
            event.accountId(),
            event.timestamp(),
            null,
            event.bindingSchemaPin()
        );
        var second = new TelemetryReceivedEvent(
            event.eventId(),
            event.deviceId(),
            event.sourceId(),
            event.timestamp(),
            changed,
            event.receivedAt(),
            "v1",
            null,
            "ingestion-service",
            event.accountId(),
            event.timestamp(),
            null,
            event.bindingSchemaPin()
        );
        capture.capture(first);
        assertEquals(
            "OBSERVATION_EVENT_CONFLICT",
            assertThrows(ObservationCaptureUnavailable.class, () ->
                capture.capture(second)
            ).getMessage()
        );
    }

    private Fixture fixture() {
        return fixture(1, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    }

    @Test
    void actualInternalOwnerReadReportsPersistedStateWithoutMintingGenerations() throws Exception {
        var fixture = fixture();
        assertEquals("SCHEMA_PENDING", ownerRead(fixture).state());
        assertTrue(generations.find(fixture.schema().pin().bindingId()).isEmpty());
        schemas.apply(fixture.schema());
        assertEquals("AWAITING_INPUT", ownerRead(fixture).state());
        assertTrue(generations.find(fixture.schema().pin().bindingId()).isEmpty());
        capture.capture(event(fixture, UUID.randomUUID(), true, "ready"));
        var result = ownerRead(fixture);
        assertEquals("READY", result.state());
        assertTrue(result.journalReady());
        assertFalse(result.ruleExecutionReady());
        assertFalse(result.negativeEvidenceReady());
        assertEquals(
            generations.find(fixture.schema().pin().bindingId()).orElseThrow().id(),
            result.inputGeneration()
        );
        assertEquals("0", result.generationStartCheckpoint());
        assertEquals("1", result.headCheckpoint());
        deletion.close(fixture.schema().pin().deviceId(), false);
        assertEquals("DISABLED", ownerRead(fixture).state());
    }

    @Test
    void internalReadRequiresTokenEvenWhenLegacyAuthorizationIsDisabled() throws Exception {
        var fixture = fixture();
        String body = mapper.writeValueAsString(fixture.schema().pin());
        assertEquals(
            org.springframework.http.HttpStatus.UNAUTHORIZED,
            http
                .postForEntity("/internal/observations/binding-readiness", body, String.class)
                .getStatusCode()
        );
        var headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        headers.set("X-Internal-Service-Token", "dev-internal-token");
        String invalid = body.substring(0, body.length() - 1) + ",\"journalReady\":true}";
        assertEquals(
            org.springframework.http.HttpStatus.BAD_REQUEST,
            http
                .postForEntity(
                    "/internal/observations/binding-readiness",
                    new org.springframework.http.HttpEntity<>(invalid, headers),
                    String.class
                )
                .getStatusCode()
        );
        assertTrue(generations.find(fixture.schema().pin().bindingId()).isEmpty());
    }

    private com.voltcanary.vcl.VclObservationReadiness ownerRead(Fixture fixture) throws Exception {
        var reads = new com.voltcanary.common.web.ConfigurableOwnerReads(
            com.voltcanary.common.web.JacksonRestClients.builder(),
            http.getRootUri(),
            java.time.Duration.ofSeconds(2)
        );
        return com.voltcanary.vcl.VclObservationReadiness.parse(
            reads.observationReadiness(
                "dev-internal-token",
                mapper.writeValueAsBytes(fixture.schema().pin()),
                System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos()
            )
        );
    }

    @Test
    void pendingInputRemovesJournalReadinessWithoutChangingPersistedGeneration() throws Exception {
        var fixture = fixture();
        schemas.apply(fixture.schema());
        capture.capture(event(fixture, UUID.randomUUID(), true, "known"));
        var before = ownerRead(fixture);
        var pin = fixture.schema().pin();
        var future = fixture(2, pin.bindingId(), pin.accountId(), pin.deviceId());
        var pending = event(future, UUID.randomUUID(), false, "future schema");
        assertEquals(ObservationCapture.Outcome.PENDING, capture.capture(pending));
        var held = ownerRead(fixture);
        assertEquals("INPUT_PENDING", held.state());
        assertFalse(held.journalReady());
        assertEquals(before.inputGeneration(), held.inputGeneration());
        assertEquals(before.headCheckpoint(), held.headCheckpoint());
        schemas.apply(future.schema());
        capture.retry(pending.eventId());
        var resumed = ownerRead(fixture);
        assertTrue(resumed.journalReady());
        assertEquals(before.inputGeneration(), resumed.inputGeneration());
        assertEquals("2", resumed.headCheckpoint());
    }

    @Test
    void schemaAndReadOperationsNeverAssignAnInputGeneration() throws Exception {
        var fixture = fixture();
        UUID binding = fixture.schema().pin().bindingId();
        assertTrue(generations.find(binding).isEmpty());
        schemas.apply(fixture.schema());
        schemas.find(fixture.schema().pin());
        assertTrue(generations.find(binding).isEmpty());
        var event = event(fixture, UUID.randomUUID(), true, "first");
        capture.capture(event);
        var generation = generations.find(binding).orElseThrow();
        org.junit.jupiter.api.Assertions.assertNotEquals(binding, generation.id());
        org.junit.jupiter.api.Assertions.assertNotEquals(
            fixture.schema().pin().snapshotId(),
            generation.id()
        );
        assertEquals(0, generation.startCheckpoint());
        assertEquals(
            generation.startedAt().toString(),
            observation(event).path("recordedAt").asText()
        );
    }

    @Test
    void rebuiltCaptureComponentsResumeTheSameGenerationFromSql() throws Exception {
        var fixture = fixture();
        schemas.apply(fixture.schema());
        capture.capture(event(fixture, UUID.randomUUID(), false, "before"));
        var original = generations.find(fixture.schema().pin().bindingId()).orElseThrow();
        var rebuiltGenerations = new com.voltcanary.telemetry.observation.ObservationGenerations(
            jdbc
        );
        var rebuilt = new ObservationCapture(
            jdbc,
            mapper,
            new ObservationSchemas(jdbc),
            properties,
            new com.voltcanary.telemetry.producer.TelemetryOutboxService(jdbc, mapper),
            rebuiltGenerations
        );
        var after = event(fixture, UUID.randomUUID(), true, "after");
        new org.springframework.transaction.support.TransactionTemplate(
            transactions
        ).executeWithoutResult(ignored -> rebuilt.capture(after));
        assertEquals(
            original,
            rebuiltGenerations.find(fixture.schema().pin().bindingId()).orElseThrow()
        );
        assertEquals(original.id().toString(), observation(after).path("inputGeneration").asText());
        assertEquals("2", observation(after).path("checkpoint").asText());
    }

    @Test
    void distinctConcurrentFirstInputsShareOneGenerationAndCutover() throws Exception {
        var fixture = fixture();
        schemas.apply(fixture.schema());
        var a = event(fixture, UUID.randomUUID(), false, "a");
        var b = event(fixture, UUID.randomUUID(), true, "b");
        var first = CompletableFuture.supplyAsync(() -> capture.capture(a));
        var second = CompletableFuture.supplyAsync(() -> capture.capture(b));
        assertEquals(
            ObservationCapture.Outcome.ACCEPTED,
            first.get(10, java.util.concurrent.TimeUnit.SECONDS)
        );
        assertEquals(
            ObservationCapture.Outcome.ACCEPTED,
            second.get(10, java.util.concurrent.TimeUnit.SECONDS)
        );
        var generation = generations.find(fixture.schema().pin().bindingId()).orElseThrow();
        assertEquals(0, generation.startCheckpoint());
        assertEquals(generation.id().toString(), observation(a).path("inputGeneration").asText());
        assertEquals(generation.id().toString(), observation(b).path("inputGeneration").asText());
        assertEquals(2, count(fixture));
    }

    @Test
    void legacyFactsAndReplayRemainGenerationLessWhileNewInputStartsAfterExplicitCutover() throws Exception {
        var fixture = fixture();
        schemas.apply(fixture.schema());
        var old = event(fixture, UUID.randomUUID(), false, "legacy");
        var pin = fixture.schema().pin();
        var now = java.sql.Timestamp.from(Instant.now());
        var value = mapper
            .createObjectNode()
            .put("profile", "accepted-boolean-text-1")
            .put("inputEventId", old.eventId().toString())
            .put("eventTime", old.timestamp().toString())
            .put("receivedAt", old.receivedAt().toString())
            .put("recordedAt", old.receivedAt().toString())
            .put("checkpoint", "1")
            .put("upstreamCoverage", "UNKNOWN")
            .put("eventTimeProvenance", "UNQUALIFIED")
            .put("negativeEvidenceReady", false)
            .put("ruleExecutionReady", false);
        value.set("bindingSchemaPin", mapper.valueToTree(pin));
        value.set(
            "fields",
            com.voltcanary.telemetry.observation.ObservationProjection.project(
                old,
                mapper.readTree(fixture.schema().canonicalDefinition())
            )
        );
        String canonical = VclCanonical.encode(value);
        jdbc.update(
            "INSERT INTO cfg_observation_input(event_id,binding_id,account_id,device_id,input_digest,status,created_at,retry_after) VALUES(?,?,?,?,?,'ACCEPTED',?,?)",
            old.eventId(),
            pin.bindingId(),
            pin.accountId(),
            pin.deviceId(),
            VclCanonical.sha256(mapper.writeValueAsString(old)),
            now,
            now
        );
        jdbc.update(
            "INSERT INTO cfg_observation(event_id,binding_id,checkpoint,schema_revision,snapshot_id,occurred_at,recorded_at,canonical_observation) VALUES(?,?,1,?,?,?,?,?)",
            old.eventId(),
            pin.bindingId(),
            pin.schemaRevision(),
            pin.snapshotId(),
            java.sql.Timestamp.from(old.timestamp()),
            now,
            canonical
        );
        jdbc.update(
            "UPDATE cfg_observation_stream SET head_checkpoint=1 WHERE binding_id=?",
            pin.bindingId()
        );
        assertEquals(ObservationCapture.Outcome.REPLAY, capture.capture(old));
        assertTrue(generations.find(pin.bindingId()).isEmpty());
        var next = event(fixture, UUID.randomUUID(), true, "new");
        capture.capture(next);
        var generation = generations.find(pin.bindingId()).orElseThrow();
        assertEquals(1, generation.startCheckpoint());
        assertEquals("2", observation(next).path("checkpoint").asText());
        assertEquals(
            canonical,
            jdbc.queryForObject(
                "SELECT canonical_observation FROM cfg_observation WHERE event_id=?",
                String.class,
                old.eventId()
            )
        );
        assertNull(
            jdbc.queryForObject(
                "SELECT input_generation FROM cfg_observation WHERE event_id=?",
                UUID.class,
                old.eventId()
            )
        );
        assertEquals(ObservationCapture.Outcome.REPLAY, capture.capture(old));
        assertEquals(generation, generations.find(pin.bindingId()).orElseThrow());
        assertEquals(
            "OBSERVATION_READ_CONFLICT",
            assertThrows(ObservationCaptureUnavailable.class, () ->
                observationReads.read(journalRequest(fixture, "0", "2", 10))
            ).getMessage()
        );
        var page = observationReads.read(journalRequest(fixture, "1", "2", 10));
        assertEquals(
            next.eventId().toString(),
            page.observations().getFirst().path("inputEventId").asText()
        );
    }

    @Test
    void storageCannotAssignAnotherBindingsGenerationToAnExistingFact() {
        var a = fixture();
        var b = fixture();
        schemas.apply(a.schema());
        schemas.apply(b.schema());
        var eventA = event(a, UUID.randomUUID(), true, "a");
        capture.capture(eventA);
        capture.capture(event(b, UUID.randomUUID(), false, "b"));
        UUID otherGeneration = generations.find(b.schema().pin().bindingId()).orElseThrow().id();
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () ->
            jdbc.update(
                "UPDATE cfg_observation SET input_generation=? WHERE event_id=?",
                otherGeneration,
                eventA.eventId()
            )
        );
    }

    @Test
    void malformedOrAuthorityAmplifyingSchemaEnvelopeCannotPopulateTheReplica() throws Exception {
        var fixture = fixture();
        var envelope = mapper.valueToTree(fixture.schema().envelope());
        var extra = envelope.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) extra).put("ruleExecutionReady", true);
        assertThrows(ObservationCaptureUnavailable.class, () -> consumer.consume(extra.toString()));
        var foreign = envelope.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) foreign).put(
            "accountId",
            UUID.randomUUID().toString()
        );
        assertThrows(ObservationCaptureUnavailable.class, () ->
            consumer.consume(foreign.toString())
        );
        var fractional = envelope.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) fractional.at("/payload/pin")).put(
            "schemaRevision",
            1.5
        );
        assertThrows(ObservationCaptureUnavailable.class, () ->
            consumer.consume(fractional.toString())
        );
        var definition = mapper.readTree(fixture.schema().canonicalDefinition());
        ((com.fasterxml.jackson.databind.node.ObjectNode) definition).put(
            "ruleExecutionReady",
            true
        );
        String canonical = VclCanonical.encode(definition);
        var pin = fixture.schema().pin();
        var changed = new SourceBindingSchemaPin(
            pin.bindingId(),
            pin.accountId(),
            pin.deviceId(),
            pin.externalSourceId(),
            1,
            pin.snapshotId(),
            VclCanonical.sha256(canonical)
        );
        assertThrows(ObservationCaptureUnavailable.class, () ->
            schemas.apply(
                new SourceBindingSchemaEvent(
                    UUID.randomUUID(),
                    "v1",
                    changed,
                    canonical,
                    true,
                    Instant.now(),
                    "device-service"
                )
            )
        );
        assertEquals(
            0,
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM cfg_observation_schema WHERE binding_id=?",
                Integer.class,
                pin.bindingId()
            )
        );
    }

    @Test
    void sourceSelectionChangesDeriveANewReplayGroupWithoutLeakingTheSourceList() {
        var fixture = fixture();
        String initial = properties.schemaGroup();
        var extra = UUID.randomUUID();
        properties.setBindingIds(Set.of(fixture.schema().pin().bindingId(), extra));
        String expanded = properties.schemaGroup();
        org.junit.jupiter.api.Assertions.assertNotEquals(initial, expanded);
        properties.setBindingIds(
            new java.util.LinkedHashSet<>(List.of(extra, fixture.schema().pin().bindingId()))
        );
        assertEquals(expanded, properties.schemaGroup());
        assertFalse(expanded.contains(extra.toString()));
    }

    @Test
    void boundedJournalPagesPreserveEqualTimestampFactsAndTheirOriginalCut() throws Exception {
        var fixture = fixture();
        schemas.apply(fixture.schema());
        var first = event(fixture, UUID.randomUUID(), false, "");
        var seed = event(fixture, UUID.randomUUID(), true, "second");
        var second = new TelemetryReceivedEvent(
            seed.eventId(),
            seed.deviceId(),
            seed.sourceId(),
            first.timestamp(),
            seed.metrics(),
            first.receivedAt(),
            "v1",
            null,
            "ingestion-service",
            seed.accountId(),
            first.timestamp(),
            null,
            seed.bindingSchemaPin()
        );
        capture.capture(first);
        capture.capture(second);
        var request = journalRequest(fixture, "0", "2", 1);
        var page = observationReads.read(request);
        assertEquals("1", page.nextCheckpoint());
        assertTrue(page.hasMore());
        assertFalse(page.observations().getFirst().at("/fields/0/value").asBoolean());
        assertEquals("", page.observations().getFirst().at("/fields/1/value").asText());
        capture.capture(event(fixture, UUID.randomUUID(), false, "later head"));
        var remaining = observationReads.read(journalRequest(fixture, "1", "2", 1));
        assertFalse(remaining.hasMore());
        assertEquals(
            second.eventId().toString(),
            remaining.observations().getFirst().path("inputEventId").asText()
        );
        assertEquals(
            page.observations().getFirst().path("eventTime"),
            remaining.observations().getFirst().path("eventTime")
        );
        assertEquals(request, com.voltcanary.vcl.VclObservationPage.parse(page.bytes()).request());
        assertFalse(remaining.ruleExecutionReady());
        assertEquals("UNKNOWN", remaining.upstreamCoverage());
    }

    @Test
    void journalReadsRejectGenerationRetargetingFutureCutsAndPendingOrDeletedSources() {
        var fixture = fixture();
        schemas.apply(fixture.schema());
        capture.capture(event(fixture, UUID.randomUUID(), true, "ready"));
        var request = journalRequest(fixture, "0", "1", 1);
        var foreignGeneration = new com.voltcanary.vcl.VclObservationRead(
            request.pin(),
            UUID.randomUUID(),
            "0",
            "1",
            1
        );
        assertEquals(
            "OBSERVATION_READ_CONFLICT",
            assertThrows(ObservationCaptureUnavailable.class, () ->
                observationReads.read(foreignGeneration)
            ).getMessage()
        );
        assertEquals(
            "OBSERVATION_READ_CONFLICT",
            assertThrows(ObservationCaptureUnavailable.class, () ->
                observationReads.read(journalRequest(fixture, "0", "2", 1))
            ).getMessage()
        );
        properties.setEnabled(false);
        assertEquals(
            "SOURCE_CAPTURE_UNAVAILABLE",
            assertThrows(ObservationCaptureUnavailable.class, () ->
                observationReads.read(request)
            ).getMessage()
        );
        properties.setEnabled(true);
        var future = fixture(
            2,
            fixture.schema().pin().bindingId(),
            fixture.schema().pin().accountId(),
            fixture.schema().pin().deviceId()
        );
        capture.capture(event(future, UUID.randomUUID(), false, "pending schema"));
        assertEquals(
            "SOURCE_CAPTURE_UNAVAILABLE",
            assertThrows(ObservationCaptureUnavailable.class, () ->
                observationReads.read(request)
            ).getMessage()
        );
        deletion.close(fixture.schema().pin().deviceId(), true);
        assertEquals(
            "SOURCE_CAPTURE_UNAVAILABLE",
            assertThrows(ObservationCaptureUnavailable.class, () ->
                observationReads.read(request)
            ).getMessage()
        );
    }

    @Test
    void missingRowsAndCanonicalPayloadCorruptionCannotBecomeAnEmptyCompleteRead() throws Exception {
        var fixture = fixture();
        schemas.apply(fixture.schema());
        var first = event(fixture, UUID.randomUUID(), true, "a");
        var second = event(fixture, UUID.randomUUID(), false, "b");
        capture.capture(first);
        capture.capture(second);
        var value = observation(first);
        ((com.fasterxml.jackson.databind.node.ObjectNode) value).put("checkpoint", "9");
        jdbc.update(
            "UPDATE cfg_observation SET canonical_observation=? WHERE event_id=?",
            VclCanonical.encode(value),
            first.eventId()
        );
        assertEquals(
            "OBSERVATION_JOURNAL_CORRUPT",
            assertThrows(ObservationCaptureUnavailable.class, () ->
                observationReads.read(journalRequest(fixture, "0", "2", 128))
            ).getMessage()
        );
        jdbc.update("DELETE FROM cfg_observation WHERE event_id=?", second.eventId());
        assertEquals(
            "OBSERVATION_JOURNAL_CORRUPT",
            assertThrows(ObservationCaptureUnavailable.class, () ->
                observationReads.read(journalRequest(fixture, "1", "2", 128))
            ).getMessage()
        );
    }

    @Test
    void observationReadRequiresACompletedInputReceipt() {
        var fixture = fixture();
        schemas.apply(fixture.schema());
        var input = event(fixture, UUID.randomUUID(), true, "accepted");
        capture.capture(input);
        var request = journalRequest(fixture, "0", "1", 128);
        var before = observationReads.read(request);
        String digest = jdbc.queryForObject(
            "SELECT input_digest FROM cfg_observation_input WHERE event_id=?",
            String.class,
            input.eventId()
        );
        jdbc.update(
            "UPDATE cfg_observation_input SET input_digest=? WHERE event_id=?",
            "invalid",
            input.eventId()
        );
        assertEquals(
            "OBSERVATION_JOURNAL_CORRUPT",
            assertThrows(ObservationCaptureUnavailable.class, () ->
                observationReads.read(request)
            ).getMessage()
        );
        jdbc.update(
            "UPDATE cfg_observation_input SET input_digest=?,payload_json=? WHERE event_id=?",
            digest,
            "{}",
            input.eventId()
        );
        assertEquals(
            "OBSERVATION_JOURNAL_CORRUPT",
            assertThrows(ObservationCaptureUnavailable.class, () ->
                observationReads.read(request)
            ).getMessage()
        );
        jdbc.update(
            "UPDATE cfg_observation_input SET payload_json=NULL WHERE event_id=?",
            input.eventId()
        );
        assertEquals(before, observationReads.read(request));
    }

    @Test
    void mixedSchemaGenerationsRequireAnExplicitMatchingCheckpointRange() throws Exception {
        var first = fixture();
        schemas.apply(first.schema());
        var original = event(first, UUID.randomUUID(), true, "old schema");
        capture.capture(original);
        var oldObservation = observation(original);
        var second = fixture(
            2,
            first.schema().pin().bindingId(),
            first.schema().pin().accountId(),
            first.schema().pin().deviceId()
        );
        schemas.apply(second.schema());
        capture.capture(event(second, UUID.randomUUID(), false, "new schema"));
        assertEquals(ObservationCapture.Outcome.REPLAY, capture.capture(original));
        assertEquals(oldObservation, observation(original));
        assertEquals(
            "OBSERVATION_READ_CONFLICT",
            assertThrows(ObservationCaptureUnavailable.class, () ->
                observationReads.read(journalRequest(second, "0", "2", 128))
            ).getMessage()
        );
        var page = observationReads.read(journalRequest(second, "1", "2", 128));
        assertEquals("2", page.nextCheckpoint());
        assertEquals(
            second.schema().pin().snapshotId().toString(),
            page.observations().getFirst().at("/bindingSchemaPin/snapshotId").asText()
        );
    }

    @Test
    void largeUnicodeValuesUseAByteBoundedPrefixWithoutDroppingFacts() {
        var fixture = fixture();
        schemas.apply(fixture.schema());
        for (int i = 0; i < 120; i++) capture.capture(
            event(fixture, UUID.randomUUID(), i % 2 == 0, "界".repeat(512))
        );
        var page = observationReads.read(journalRequest(fixture, "0", "120", 128));
        assertTrue(page.hasMore());
        assertEquals("PAGE_BYTES", page.truncationReason());
        assertTrue(page.observations().size() < 120);
        assertTrue(page.bytes().length <= com.voltcanary.vcl.VclObservationPage.MAX_BYTES);
        int firstCount = page.observations().size();
        var next = observationReads.read(
            journalRequest(fixture, page.nextCheckpoint(), "120", 128)
        );
        assertEquals(120, firstCount + next.observations().size());
        assertFalse(next.hasMore());
        assertEquals(
            "界".repeat(512),
            next.observations().getLast().at("/fields/1/value").asText()
        );
    }

    @Test
    void internalJournalReadRequiresTheMandatoryTokenAndUsesTheSharedWireCodec() throws Exception {
        var fixture = fixture();
        schemas.apply(fixture.schema());
        capture.capture(event(fixture, UUID.randomUUID(), false, "private observation value"));
        var request = journalRequest(fixture, "0", "1", 1);
        var headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        assertEquals(
            org.springframework.http.HttpStatus.UNAUTHORIZED,
            http
                .postForEntity(
                    "/internal/observations/read",
                    new org.springframework.http.HttpEntity<>(request.bytes(), headers),
                    String.class
                )
                .getStatusCode()
        );
        headers.set("X-Internal-Service-Token", "dev-internal-token");
        var response = http.postForEntity(
            "/internal/observations/read",
            new org.springframework.http.HttpEntity<>(request.bytes(), headers),
            byte[].class
        );
        assertEquals(org.springframework.http.HttpStatus.OK, response.getStatusCode());
        var page = com.voltcanary.vcl.VclObservationPage.parse(response.getBody());
        assertEquals(request, page.request());
        assertEquals(
            "private observation value",
            page.observations().getFirst().at("/fields/1/value").asText()
        );
        var invalid = mapper.readTree(request.bytes());
        ((com.fasterxml.jackson.databind.node.ObjectNode) invalid).put("ruleExecutionReady", true);
        assertEquals(
            org.springframework.http.HttpStatus.BAD_REQUEST,
            http
                .postForEntity(
                    "/internal/observations/read",
                    new org.springframework.http.HttpEntity<>(invalid.toString(), headers),
                    String.class
                )
                .getStatusCode()
        );
    }

    @Test
    void observationValueIntegrityAndLegacyDigestAbsenceCannotBeSilentlyRepaired() throws Exception {
        var fixture = fixture();
        schemas.apply(fixture.schema());
        var event = event(fixture, UUID.randomUUID(), false, "original");
        capture.capture(event);
        var original = observation(event);
        var changed = original.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) changed.at("/fields/0")).put(
            "value",
            true
        );
        jdbc.update(
            "UPDATE cfg_observation SET canonical_observation=? WHERE event_id=?",
            VclCanonical.encode(changed),
            event.eventId()
        );
        assertEquals(
            "OBSERVATION_JOURNAL_CORRUPT",
            assertThrows(ObservationCaptureUnavailable.class, () ->
                observationReads.read(journalRequest(fixture, "0", "1", 1))
            ).getMessage()
        );
        String canonical = VclCanonical.encode(original);
        jdbc.update(
            "UPDATE cfg_observation SET canonical_observation=?,canonical_digest=NULL WHERE event_id=?",
            canonical,
            event.eventId()
        );
        assertEquals(ObservationCapture.Outcome.REPLAY, capture.capture(event));
        assertEquals(
            "OBSERVATION_INTEGRITY_UNAVAILABLE",
            assertThrows(ObservationCaptureUnavailable.class, () ->
                observationReads.read(journalRequest(fixture, "0", "1", 1))
            ).getMessage()
        );
        assertNull(
            jdbc.queryForObject(
                "SELECT canonical_digest FROM cfg_observation WHERE event_id=?",
                String.class,
                event.eventId()
            )
        );
        assertEquals(
            canonical,
            jdbc.queryForObject(
                "SELECT canonical_observation FROM cfg_observation WHERE event_id=?",
                String.class,
                event.eventId()
            )
        );
    }

    @Test
    void v020UpgradePreservesExistingGenerationBearingPayloadsWithoutBackfillingDigests() throws Exception {
        String schema = "obs_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE SCHEMA \"" + schema + "\"");
        var original = java.util.Objects.requireNonNull(jdbc.getDataSource());
        var connection = original.getConnection();
        String originalSchema = connection.getSchema();
        var scoped = new org.springframework.jdbc.datasource.SingleConnectionDataSource(
            connection,
            true
        );
        try {
            connection.setSchema(schema);
            new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
                new org.springframework.core.io.ClassPathResource(
                    "db/migration/V018__accepted_observations.sql"
                ),
                new org.springframework.core.io.ClassPathResource(
                    "db/migration/V019__observation_input_generation.sql"
                )
            ).execute(scoped);
            var sql = new JdbcTemplate(scoped);
            var fixture = fixture();
            var pin = fixture.schema().pin();
            var event = event(fixture, UUID.randomUUID(), false, "legacy original");
            UUID generation = UUID.randomUUID();
            var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
            var value = mapper
                .createObjectNode()
                .put("profile", "accepted-boolean-text-2")
                .put("inputEventId", event.eventId().toString())
                .put("inputGeneration", generation.toString())
                .put("eventTime", event.timestamp().toString())
                .put("receivedAt", event.receivedAt().toString())
                .put("recordedAt", now.toString())
                .put("checkpoint", "1")
                .put("upstreamCoverage", "UNKNOWN")
                .put("eventTimeProvenance", "UNQUALIFIED")
                .put("negativeEvidenceReady", false)
                .put("ruleExecutionReady", false);
            value.set("bindingSchemaPin", mapper.valueToTree(pin));
            value.set(
                "fields",
                com.voltcanary.telemetry.observation.ObservationProjection.project(
                    event,
                    mapper.readTree(fixture.schema().canonicalDefinition())
                )
            );
            String canonical = VclCanonical.encode(value);
            sql.update(
                "INSERT INTO cfg_observation_stream(binding_id,account_id,device_id,head_checkpoint,input_generation,generation_start_checkpoint,generation_started_at) VALUES(?,?,?,1,?,0,?)",
                pin.bindingId(),
                pin.accountId(),
                pin.deviceId(),
                generation,
                java.sql.Timestamp.from(now)
            );
            sql.update(
                "INSERT INTO cfg_observation_input(event_id,binding_id,account_id,device_id,input_digest,status,created_at,retry_after) VALUES(?,?,?,?,?,'ACCEPTED',?,?)",
                event.eventId(),
                pin.bindingId(),
                pin.accountId(),
                pin.deviceId(),
                VclCanonical.sha256(mapper.writeValueAsString(event)),
                java.sql.Timestamp.from(now),
                java.sql.Timestamp.from(now)
            );
            sql.update(
                "INSERT INTO cfg_observation(event_id,binding_id,checkpoint,schema_revision,snapshot_id,occurred_at,recorded_at,canonical_observation,input_generation) VALUES(?,?,1,?,?,?,?,?,?)",
                event.eventId(),
                pin.bindingId(),
                pin.schemaRevision(),
                pin.snapshotId(),
                java.sql.Timestamp.from(event.timestamp()),
                java.sql.Timestamp.from(now),
                canonical,
                generation
            );
            new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
                new org.springframework.core.io.ClassPathResource(
                    "db/migration/V020__observation_integrity.sql"
                )
            ).execute(scoped);
            assertEquals(
                canonical,
                sql.queryForObject(
                    "SELECT canonical_observation FROM cfg_observation WHERE event_id=?",
                    String.class,
                    event.eventId()
                )
            );
            assertNull(
                sql.queryForObject(
                    "SELECT canonical_digest FROM cfg_observation WHERE event_id=?",
                    String.class,
                    event.eventId()
                )
            );
            assertEquals(
                generation,
                sql.queryForObject(
                    "SELECT input_generation FROM cfg_observation WHERE event_id=?",
                    UUID.class,
                    event.eventId()
                )
            );
            assertEquals(
                1L,
                sql.queryForObject(
                    "SELECT head_checkpoint FROM cfg_observation_stream WHERE binding_id=?",
                    Long.class,
                    pin.bindingId()
                )
            );
        } finally {
            try {
                connection.setSchema(originalSchema);
            } finally {
                connection.close();
            }
            jdbc.execute("DROP SCHEMA \"" + schema + "\" CASCADE");
        }
    }

    private com.voltcanary.vcl.VclObservationRead journalRequest(
        Fixture fixture,
        String after,
        String through,
        int limit
    ) {
        var pin = fixture.schema().pin();
        var actual = new com.voltcanary.vcl.VclObservationReadiness.Pin(
            pin.bindingId(),
            pin.accountId(),
            pin.deviceId(),
            pin.externalSourceId(),
            pin.schemaRevision(),
            pin.snapshotId(),
            pin.definitionDigest()
        );
        return new com.voltcanary.vcl.VclObservationRead(
            actual,
            generations.find(pin.bindingId()).orElseThrow().id(),
            after,
            through,
            limit
        );
    }

    private Fixture fixture(long revision, UUID binding, UUID account, UUID device) {
        var field = UUID.randomUUID();
        var text = UUID.randomUUID();
        var definition = mapper
            .createObjectNode()
            .put("id", binding.toString())
            .put("schemaRevision", revision)
            .put("profile", VclSourceBindingProfile.VERSION)
            .put("configurationDigest", "a".repeat(64));
        var fields = definition.putArray("fields");
        fields
            .addObject()
            .put("id", field.toString())
            .put("label", "Switch")
            .put("type", "BOOLEAN")
            .put("nullable", true);
        fields
            .addObject()
            .put("id", text.toString())
            .put("label", "Status")
            .put("type", "TEXT")
            .put("nullable", true)
            .put("maxLength", 512);
        var mappings = definition.putArray("fieldMappings");
        mappings
            .addObject()
            .put("fieldId", field.toString())
            .put("sourceKey", "switch")
            .put("path", "input.switch")
            .put("sourceType", "boolean")
            .put("sourceUnit", "boolean")
            .put("vclReady", true);
        mappings
            .addObject()
            .put("fieldId", text.toString())
            .put("sourceKey", "status")
            .put("path", "input.status")
            .put("sourceType", "string")
            .put("sourceUnit", "")
            .put("vclReady", true);
        definition.putArray("unsupportedFields");
        String canonical = VclCanonical.encode(definition);
        var pin = new SourceBindingSchemaPin(
            binding,
            account,
            device,
            "source-" + binding,
            revision,
            UUID.randomUUID(),
            VclCanonical.sha256(canonical)
        );
        var ids = new java.util.HashSet<>(properties.getBindingIds());
        ids.add(binding);
        properties.setBindingIds(ids);
        return new Fixture(
            new SourceBindingSchemaEvent(
                UUID.randomUUID(),
                "v1",
                pin,
                canonical,
                true,
                Instant.now(),
                "device-service"
            ),
            field,
            text
        );
    }

    private TelemetryReceivedEvent event(Fixture fixture, UUID id, boolean state, String text) {
        var pin = fixture.schema().pin();
        var values = new java.util.LinkedHashMap<String, Object>();
        values.put(
            "switch",
            Map.of(
                "value",
                state,
                "type",
                "boolean",
                "label",
                "Switch",
                "unit",
                "boolean",
                "display",
                true
            )
        );
        if (text != null) values.put(
            "status",
            Map.of("value", text, "type", "string", "label", "Status", "unit", "", "display", true)
        );
        var time = Instant.now().minusSeconds(2).truncatedTo(ChronoUnit.MICROS);
        return new TelemetryReceivedEvent(
            id,
            pin.deviceId(),
            pin.externalSourceId(),
            time,
            Map.of("configuredFields", values),
            time.plusSeconds(1),
            "v1",
            null,
            "ingestion-service",
            pin.accountId(),
            time,
            null,
            pin
        );
    }

    private int count(Fixture fixture) {
        return jdbc.queryForObject(
            "SELECT COUNT(*) FROM cfg_observation WHERE binding_id=?",
            Integer.class,
            fixture.schema().pin().bindingId()
        );
    }

    private JsonNode observation(TelemetryReceivedEvent event) throws Exception {
        return mapper.readTree(
            jdbc.queryForObject(
                "SELECT canonical_observation FROM cfg_observation WHERE event_id=?",
                String.class,
                event.eventId()
            )
        );
    }
}
