package com.voltcanary.telemetry.observation;

import com.fasterxml.jackson.databind.JsonNode;
import com.voltcanary.common.events.device.SourceBindingSchemaEvent;
import com.voltcanary.common.events.telemetry.SourceBindingSchemaPin;
import com.voltcanary.common.web.JdbcDeviceFence;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclParser;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Append-only schema replicas are typing evidence, never current resource authority. */
@Component
public class ObservationSchemas {

    private final JdbcTemplate jdbc;
    private final JdbcDeviceFence fence;
    private final VclParser parser = new VclParser();

    public ObservationSchemas(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        fence = new JdbcDeviceFence(jdbc);
    }

    @Transactional
    public void apply(SourceBindingSchemaEvent event) {
        var pin = event.pin();
        var definition = definition(pin, event.canonicalDefinition());
        ObservationProjection.validateSchema(definition);
        if (!fence.writable(pin.deviceId())) return;
        var existing = find(pin);
        if (existing.isPresent()) {
            if (!existing.get().equals(definition)) throw new ObservationCaptureUnavailable(
                "OBSERVATION_SCHEMA_CONFLICT"
            );
            return;
        }
        var streams = jdbc.queryForList(
            "SELECT account_id,device_id FROM cfg_observation_stream WHERE binding_id=?",
            pin.bindingId()
        );
        if (streams.isEmpty()) jdbc.update(
            "INSERT INTO cfg_observation_stream(binding_id,account_id,device_id) VALUES(?,?,?)",
            pin.bindingId(),
            pin.accountId(),
            pin.deviceId()
        );
        else if (
            !pin.accountId().equals(streams.getFirst().get("account_id")) ||
            !pin.deviceId().equals(streams.getFirst().get("device_id"))
        ) {
            throw new ObservationCaptureUnavailable("OBSERVATION_SCOPE_CHANGED");
        }
        if (
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM cfg_observation_schema WHERE binding_id=?",
                Integer.class,
                pin.bindingId()
            ) >= 1000
        ) {
            throw new ObservationCaptureUnavailable("OBSERVATION_SCHEMA_QUOTA");
        }
        jdbc.update(
            "INSERT INTO cfg_observation_schema(binding_id,schema_revision,snapshot_id,definition_digest,external_source_id,canonical_definition) VALUES(?,?,?,?,?,?)",
            pin.bindingId(),
            pin.schemaRevision(),
            pin.snapshotId(),
            pin.definitionDigest(),
            pin.externalSourceId(),
            event.canonicalDefinition()
        );
    }

    public Optional<JsonNode> find(SourceBindingSchemaPin pin) {
        return jdbc
            .query(
                """
                SELECT r.snapshot_id,r.definition_digest,r.external_source_id,r.canonical_definition,s.account_id,s.device_id
                FROM cfg_observation_schema r JOIN cfg_observation_stream s ON s.binding_id=r.binding_id
                WHERE r.binding_id=? AND r.schema_revision=?
                """,
                (row, index) -> {
                    if (
                        !pin.accountId().equals(row.getObject("account_id", UUID.class)) ||
                        !pin.deviceId().equals(row.getObject("device_id", UUID.class)) ||
                        !pin.snapshotId().equals(row.getObject("snapshot_id", UUID.class)) ||
                        !pin.definitionDigest().equals(row.getString("definition_digest")) ||
                        !pin.externalSourceId().equals(row.getString("external_source_id"))
                    ) {
                        throw new ObservationCaptureUnavailable("OBSERVATION_SCHEMA_CONFLICT");
                    }
                    return definition(pin, row.getString("canonical_definition"));
                },
                pin.bindingId(),
                pin.schemaRevision()
            )
            .stream()
            .findFirst();
    }

    private JsonNode definition(SourceBindingSchemaPin pin, String text) {
        var value = parser.readJson(text.getBytes(StandardCharsets.UTF_8));
        if (
            !pin.definitionDigest().equals(VclCanonical.sha256(text)) ||
            !VclCanonical.encode(value).equals(text) ||
            !pin.bindingId().toString().equals(value.path("id").asText()) ||
            !value.path("schemaRevision").isIntegralNumber() ||
            value.path("schemaRevision").asLong() != pin.schemaRevision()
        ) throw new ObservationCaptureUnavailable("OBSERVATION_SCHEMA_INVALID");
        return value;
    }
}
