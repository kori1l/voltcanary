package com.voltcanary.telemetry.observation;

import com.fasterxml.jackson.databind.JsonNode;
import com.voltcanary.common.events.telemetry.SourceBindingSchemaPin;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclObservationPage;
import com.voltcanary.vcl.VclObservationRead;
import com.voltcanary.vcl.VclObservationReadiness;
import com.voltcanary.vcl.VclParser;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Read-only fixed-generation journal prefixes; never supplies upstream completeness or execution authority. */
@Service
@RequiredArgsConstructor
public class ObservationReads {
    private static final int ROW_BYTES = 131072;
    private static final int PAGE_ROW_BYTES = 240000;
    private final JdbcTemplate jdbc;
    private final ObservationReadiness readiness;
    private final ObservationSchemas schemas;
    private final VclParser parser = new VclParser();

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public VclObservationPage read(VclObservationRead request) {
        var current = readiness.read(request.pin());
        if (!current.journalReady()) fail("SOURCE_CAPTURE_UNAVAILABLE");
        long after = VclObservationReadiness.checkpoint(request.afterCheckpoint());
        long through = VclObservationReadiness.checkpoint(request.throughCheckpoint());
        if (!request.inputGeneration().equals(current.inputGeneration())
                || after < VclObservationReadiness.checkpoint(current.generationStartCheckpoint())
                || through > VclObservationReadiness.checkpoint(current.headCheckpoint())) fail("OBSERVATION_READ_CONFLICT");
        var pin = request.pin();
        var schemaPin = new SourceBindingSchemaPin(pin.bindingId(), pin.accountId(), pin.deviceId(), pin.externalSourceId(),
                pin.schemaRevision(), pin.snapshotId(), pin.definitionDigest());
        var definition = schemas.find(schemaPin).orElseThrow(() -> new ObservationCaptureUnavailable("SOURCE_CAPTURE_UNAVAILABLE"));
        var types = new HashMap<String, String>();
        boolean ranged = request.eventFrom() != null;
        definition.path("fields").forEach(field -> types.put(field.path("id").asText(), field.path("type").asText()));
        var page = jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<VclObservationPage>) connection -> {
            var values = new ArrayList<JsonNode>();
            int bytes = 0;
            boolean byteLimit = false;
            boolean rowLimit = false;
            long checkpoint = after;
            try (var query = connection.prepareStatement("""
                    SELECT o.event_id,o.checkpoint,o.schema_revision,o.snapshot_id,o.occurred_at,o.recorded_at,o.canonical_digest,
                    CASE WHEN OCTET_LENGTH(o.canonical_observation)<=? THEN o.canonical_observation ELSE NULL END AS payload,
                    i.status,i.binding_id,i.account_id,i.device_id,i.input_digest,
                    i.payload_json IS NULL AS receipt_payload_cleared
                    FROM cfg_observation o LEFT JOIN cfg_observation_input i ON i.event_id=o.event_id
                    WHERE o.binding_id=? AND o.input_generation=? AND o.checkpoint>? AND o.checkpoint<=?
                    """ + (ranged ? " AND o.occurred_at>=? AND o.occurred_at<?" : "") + " ORDER BY o.checkpoint LIMIT ?")) {
                query.setQueryTimeout(2);
                query.setFetchSize(request.limit());
                query.setInt(1, ROW_BYTES);
                query.setObject(2, pin.bindingId());
                query.setObject(3, request.inputGeneration());
                query.setLong(4, after);
                query.setLong(5, through);
                if (ranged) {
                    query.setObject(6, Instant.parse(request.eventFrom()).atOffset(java.time.ZoneOffset.UTC));
                    query.setObject(7, Instant.parse(request.eventUntil()).atOffset(java.time.ZoneOffset.UTC));
                }
                query.setInt(ranged ? 8 : 6, request.limit() + (ranged ? 1 : 0));
                try (var rows = query.executeQuery()) {
                    while (rows.next()) {
                        if (values.size() == request.limit()) { rowLimit = true; break; }
                        if (rows.getLong("schema_revision") != pin.schemaRevision() || !pin.snapshotId().equals(rows.getObject("snapshot_id", UUID.class))) fail("OBSERVATION_READ_CONFLICT");
                        long next = rows.getLong("checkpoint");
                        if (next <= checkpoint || next > through || !ranged && next != checkpoint + 1
                                || !"ACCEPTED".equals(rows.getString("status"))
                                || !pin.bindingId().equals(rows.getObject("binding_id", UUID.class)) || !pin.accountId().equals(rows.getObject("account_id", UUID.class))
                                || !pin.deviceId().equals(rows.getObject("device_id", UUID.class))) fail("OBSERVATION_JOURNAL_CORRUPT");
                        String inputDigest = rows.getString("input_digest");
                        if (!rows.getBoolean("receipt_payload_cleared") || inputDigest == null || !inputDigest.matches("[0-9a-f]{64}")) {
                            fail("OBSERVATION_JOURNAL_CORRUPT");
                        }
                        String payload = rows.getString("payload");
                        if (rows.getString("canonical_digest") == null) fail("OBSERVATION_INTEGRITY_UNAVAILABLE");
                        if (payload == null) fail("OBSERVATION_JOURNAL_CORRUPT");
                        if (!VclCanonical.sha256(payload).equals(rows.getString("canonical_digest"))) fail("OBSERVATION_JOURNAL_CORRUPT");
                        var value = parser.readJson(payload.getBytes(StandardCharsets.UTF_8));
                        if (!payload.equals(VclCanonical.encode(value)) || !rows.getObject("event_id", UUID.class).toString().equals(value.path("inputEventId").asText())
                                || !Long.toString(rows.getLong("checkpoint")).equals(value.path("checkpoint").asText())
                                || !rows.getObject("occurred_at", OffsetDateTime.class).toInstant().equals(Instant.parse(value.path("eventTime").asText()))
                                || !rows.getObject("recorded_at", OffsetDateTime.class).toInstant().equals(Instant.parse(value.path("recordedAt").asText()))) fail("OBSERVATION_JOURNAL_CORRUPT");
                        var found = new HashMap<String, String>();
                        value.path("fields").forEach(field -> {
                            if (found.putIfAbsent(field.path("fieldId").asText(), field.path("type").asText()) != null) fail("OBSERVATION_JOURNAL_CORRUPT");
                        });
                        if (!found.equals(types)) fail("OBSERVATION_JOURNAL_CORRUPT");
                        int length = payload.getBytes(StandardCharsets.UTF_8).length;
                        if (bytes + length > PAGE_ROW_BYTES) { byteLimit = true; break; }
                        values.add(value);
                        bytes += length;
                        checkpoint = next;
                    }
                }
            }
            if (ranged) return VclObservationPage.ranged(request, values, byteLimit || rowLimit, byteLimit ? "PAGE_BYTES" : "PAGE_ROWS");
            if (checkpoint < through && !byteLimit && values.size() < request.limit()) fail("OBSERVATION_JOURNAL_CORRUPT");
            return VclObservationPage.of(request, values, byteLimit ? "PAGE_BYTES" : "PAGE_ROWS");
        });
        if (page == null) fail("OBSERVATION_JOURNAL_CORRUPT");
        page.requireSchema(definition);
        page.bytes();
        return page;
    }

    private static void fail(String code) { throw new ObservationCaptureUnavailable(code); }
}
