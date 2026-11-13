package com.voltcanary.telemetry.observation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.common.events.EventEnvelope;
import com.voltcanary.common.events.telemetry.TelemetryReceivedEvent;
import com.voltcanary.common.web.JdbcDeviceFence;
import com.voltcanary.telemetry.producer.TelemetryOutboxService;
import com.voltcanary.vcl.VclCanonical;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Dense immutable event journal, independent of latest measurements and timestamp coalescing. */
@Service
public class ObservationCapture {
    public static final String TOPIC = "telemetry.observations.accepted.v1";
    public enum Outcome { SKIPPED, PENDING, ACCEPTED, REPLAY, DELETED }
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ObservationSchemas schemas;
    private final ObservationCaptureProperties properties;
    private final TelemetryOutboxService outbox;
    private final JdbcDeviceFence fence;
    private final ObservationGenerations generations;

    public ObservationCapture(JdbcTemplate jdbc, ObjectMapper mapper, ObservationSchemas schemas,
            ObservationCaptureProperties properties, TelemetryOutboxService outbox, ObservationGenerations generations) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.schemas = schemas;
        this.properties = properties;
        this.outbox = outbox;
        this.generations = generations;
        fence = new JdbcDeviceFence(jdbc);
    }

    @Transactional
    public Outcome capture(TelemetryReceivedEvent event) {
        if (event == null || event.bindingSchemaPin() == null || !properties.captures(event.bindingSchemaPin().bindingId())) return Outcome.SKIPPED;
        try { return capture(event, mapper.writeValueAsString(event)); }
        catch (ObservationCaptureUnavailable failure) { throw failure; }
        catch (RuntimeException | java.io.IOException failure) { throw new ObservationCaptureUnavailable("OBSERVATION_STORAGE_UNAVAILABLE"); }
    }

    @Transactional
    public Outcome retry(UUID eventId) {
        var inputs = jdbc.queryForList("SELECT payload_json FROM cfg_observation_input WHERE event_id=? AND status='PENDING'", String.class, eventId);
        if (inputs.isEmpty()) return Outcome.REPLAY;
        try { return capture(mapper.readValue(inputs.getFirst(), TelemetryReceivedEvent.class), inputs.getFirst()); }
        catch (ObservationCaptureUnavailable failure) { throw failure; }
        catch (RuntimeException | java.io.IOException failure) { throw new ObservationCaptureUnavailable("OBSERVATION_STORAGE_UNAVAILABLE"); }
    }

    private Outcome capture(TelemetryReceivedEvent event, String wire) {
        var pin = event.bindingSchemaPin();
        if (pin == null || !properties.captures(pin.bindingId())) return Outcome.SKIPPED;
        if (event.eventId() == null || !pin.accountId().equals(event.accountId()) || !pin.deviceId().equals(event.deviceId())
                || !pin.externalSourceId().equals(event.sourceId()) || event.timestamp() == null || event.receivedAt() == null
                || !event.timestamp().equals(event.occurredAt()) || !"ingestion-service".equals(event.producer())
                || wire.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 1_048_576 || event.timestamp().getNano() % 1000 != 0) {
            throw new ObservationCaptureUnavailable("OBSERVATION_INPUT_INVALID");
        }
        if (!fence.writable(event.deviceId())) return Outcome.DELETED;
        String inputDigest = VclCanonical.sha256(wire);
        var existing = jdbc.queryForList("SELECT input_digest,status FROM cfg_observation_input WHERE event_id=?", event.eventId());
        if (!existing.isEmpty()) {
            if (!inputDigest.equals(existing.getFirst().get("input_digest"))) throw new ObservationCaptureUnavailable("OBSERVATION_EVENT_CONFLICT");
            if ("ACCEPTED".equals(existing.getFirst().get("status"))) return Outcome.REPLAY;
        }
        var schema = schemas.find(pin);
        if (schema.isEmpty()) {
            if (!existing.isEmpty()) return Outcome.PENDING;
            // Device fences serialize a source's count; the limit is deliberately per binding.
            if (jdbc.queryForObject("SELECT COUNT(*) FROM cfg_observation_input WHERE binding_id=? AND status='PENDING'", Integer.class, pin.bindingId()) >= properties.getPendingLimit()) {
                throw new ObservationCaptureUnavailable("OBSERVATION_PENDING_QUOTA");
            }
            insertInput(event, inputDigest, "PENDING", wire);
            return Outcome.PENDING;
        }
        var fields = ObservationProjection.project(event, schema.get());
        Long head = jdbc.queryForObject("SELECT head_checkpoint FROM cfg_observation_stream WHERE binding_id=? FOR UPDATE", Long.class, pin.bindingId());
        if (head >= properties.getObservationLimit()) throw new ObservationCaptureUnavailable("OBSERVATION_CAPACITY_REACHED");
        long checkpoint = Math.addExact(head, 1);
        var recordedAt = now();
        var generation = generations.begin(pin, head, recordedAt);
        var value = mapper.createObjectNode().put("profile", com.voltcanary.vcl.VclObservationProfile.VERSION).put("inputEventId", event.eventId().toString())
                .put("inputGeneration", generation.id().toString())
                .put("eventTime", event.timestamp().toString()).put("receivedAt", event.receivedAt().toString()).put("recordedAt", recordedAt.toString())
                .put("checkpoint", Long.toString(checkpoint)).put("upstreamCoverage", "UNKNOWN").put("eventTimeProvenance", "UNQUALIFIED")
                .put("negativeEvidenceReady", false).put("ruleExecutionReady", false);
        value.set("bindingSchemaPin", mapper.valueToTree(pin));
        value.set("fields", fields);
        try { new com.voltcanary.vcl.VclParser().readJson(mapper.writeValueAsBytes(value)); }
        catch (java.io.IOException | RuntimeException invalid) { throw new ObservationCaptureUnavailable("OBSERVATION_VALUE_INVALID"); }
        // All scalar values are Boolean/TEXT or timestamps/UUIDs; no legacy numeric-to-JCS conversion.
        String canonical = VclCanonical.encode(value);
        if (existing.isEmpty()) insertInput(event, inputDigest, "ACCEPTED", null);
        else jdbc.update("UPDATE cfg_observation_input SET status='ACCEPTED',payload_json=NULL WHERE event_id=?", event.eventId());
        var position = event.sourcePosition();
        if (position != null) SourcePositionProgress.record(jdbc, pin.bindingId(), generation.id(), pin.snapshotId(), position);
        jdbc.update("""
                INSERT INTO cfg_observation(event_id,binding_id,checkpoint,schema_revision,snapshot_id,occurred_at,recorded_at,
                    canonical_observation,input_generation,canonical_digest,producer_epoch,producer_sequence)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
                """, event.eventId(), pin.bindingId(), checkpoint, pin.schemaRevision(), pin.snapshotId(), Timestamp.from(event.timestamp()),
                Timestamp.from(recordedAt), canonical, generation.id(), VclCanonical.sha256(canonical),
                position == null ? null : position.epoch(), position == null ? null : position.ordinal());
        jdbc.update("UPDATE cfg_observation_stream SET head_checkpoint=? WHERE binding_id=?", checkpoint, pin.bindingId());
        var envelope = new EventEnvelope<>(event.eventId(), "ObservationAccepted", 1, event.timestamp(),
                event.correlationId() == null ? event.eventId().toString() : event.correlationId(), "telemetry-service", event.accountId(), value);
        outbox.enqueue("observations:" + event.eventId(), TOPIC, pin.bindingId().toString(), envelope);
        return Outcome.ACCEPTED;
    }

    private void insertInput(TelemetryReceivedEvent event, String digest, String status, String payload) {
        var now = Timestamp.from(now());
        jdbc.update("INSERT INTO cfg_observation_input(event_id,binding_id,account_id,device_id,input_digest,status,payload_json,created_at,retry_after) VALUES(?,?,?,?,?,?,?,?,?)",
                event.eventId(), event.bindingSchemaPin().bindingId(), event.accountId(), event.deviceId(), digest, status, payload, now, now);
    }

    private Instant now() {
        return jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Instant>) connection -> {
            boolean h2 = connection.getMetaData().getDatabaseProductName().equalsIgnoreCase("H2");
            try (var statement = connection.createStatement(); var rows = statement.executeQuery(h2
                    ? "SELECT EXECUTING_STATEMENT_START FROM INFORMATION_SCHEMA.SESSIONS WHERE SESSION_ID=SESSION_ID()"
                    : "SELECT clock_timestamp()")) {
                rows.next();
                return rows.getObject(1, OffsetDateTime.class).toInstant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            }
        });
    }
}
