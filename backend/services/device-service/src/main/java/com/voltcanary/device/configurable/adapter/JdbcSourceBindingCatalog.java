package com.voltcanary.device.configurable.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.voltcanary.device.configurable.domain.SourceBindingDefinition;
import com.voltcanary.device.configurable.port.SourceBindingCatalog;
import com.voltcanary.device.domain.DataSource;
import com.voltcanary.device.domain.DataSourceProtocol;
import com.voltcanary.device.domain.DataSourceType;
import com.voltcanary.device.domain.SourceStatus;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclParser;
import com.voltcanary.vcl.VclSourceBindingDescription;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Source-owned append-only schema journal, committed in the source configuration transaction. */
@Component
@RequiredArgsConstructor
public class JdbcSourceBindingCatalog implements SourceBindingCatalog {
    private record Account(UUID id, String scope) { }
    private record Current(DataSource source, String deviceStatus, String configurationDigest) { }
    private record SourceRow(DataSource source, String deviceStatus) { }
    private record Stored(UUID sourceId, long revision, UUID snapshotId, String digest, String canonical,
            String normalization, String normalizationDigest) { }
    private final JdbcTemplate jdbc;
    private final jakarta.persistence.EntityManager entities;
    private final VclParser parser = new VclParser();

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(UUID accountId, DataSource source) {
        // Source creation and updates may still be in the JPA write-behind queue.
        entities.flush();
        var locked = jdbc.queryForList("""
                SELECT id FROM data_sources WHERE id=? AND device_id IN
                (SELECT id FROM devices WHERE account_id=?) FOR UPDATE
                """, UUID.class, source.getId(), accountId);
        if (locked.isEmpty()) throw new VclException("SOURCE_BINDING_UNAVAILABLE", "");
        Current current = current(accountId, source.getId());
        var previous = stored(source.getId(), null);
        if (previous != null && definition(previous).path("configurationDigest").asText().equals(current.configurationDigest())) return;
        long revision = previous == null ? 1 : Math.addExact(previous.revision(), 1);
        var fields = SourceBindingDefinition.fields(current.source());
        var identities = identities(source.getId(), fields.stream().map(com.voltcanary.common.events.telemetry.TelemetryFieldPolicy.Field::key).toList());
        for (var field : fields) {
            if (!identities.containsKey(field.key())) {
                UUID id = UUID.randomUUID();
                jdbc.update("INSERT INTO cfg_source_field_identity(source_id,field_key,field_id) VALUES(?,?,?)",
                        source.getId(), field.key(), id);
                identities.put(field.key(), id);
            }
        }
        var projected = SourceBindingDefinition.project(current.source(), revision, identities, current.configurationDigest());
        // Check Unicode and safe metadata values before the canonicalizer can reinterpret them.
        parser.readJson(projected.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String canonical = VclCanonical.encode(projected);
        UUID snapshotId = UUID.randomUUID();
        String definitionDigest = VclCanonical.sha256(canonical);
        String normalization = normalization(current.source());
        jdbc.update("""
                INSERT INTO cfg_source_binding_snapshot(source_id,schema_revision,snapshot_id,account_id,device_id,definition_digest,
                    canonical_definition,canonical_normalization,normalization_digest)
                VALUES(?,?,?,?,?,?,?,?,?)
                """, source.getId(), revision, snapshotId, accountId, current.source().getDeviceId(),
                definitionDigest, canonical, normalization, normalizationDigest(snapshotId, definitionDigest, normalization));
    }

    private String normalization(DataSource source) {
        var payload = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        if (source.getMappingJson() != null) {
            try {
                var mapping = new com.fasterxml.jackson.databind.ObjectMapper().readTree(source.getMappingJson());
                for (String key : java.util.List.of("telemetryPolicy", "reviewedBooleanMappings")) {
                    if (mapping.has(key)) payload.set(key, mapping.path(key));
                }
            } catch (java.io.IOException failure) {
                throw new VclException("BINDING_CONFIGURATION_INVALID", "");
            }
        }
        return VclCanonical.encode(payload);
    }

    private static String normalizationDigest(UUID snapshot, String schema, String normalization) {
        return VclCanonical.sha256("SOURCE_NORMALIZATION/1\n" + snapshot + "\n" + schema + "\n" + normalization);
    }

    @Override
    @Transactional(readOnly = true)
    public JsonNode normalization(VclSourceBindingDescription source) {
        var current = describe(source.actorId(), source.id(), source.schemaRevision());
        if (!source.equals(current)) throw new VclException("METADATA_SNAPSHOT_CHANGED", "");
        var snapshot = stored(source.id(), source.schemaRevision());
        if (snapshot == null || !source.snapshotId().equals(snapshot.snapshotId())
                || !source.definitionDigest().equals(snapshot.digest())) throw new VclException("BINDING_SNAPSHOT_INTEGRITY", "");
        if (snapshot.normalization() == null) throw new VclException("MAPPING_REPLAY_UNAVAILABLE", "");
        if (!normalizationDigest(snapshot.snapshotId(), snapshot.digest(), snapshot.normalization()).equals(snapshot.normalizationDigest())) {
            throw new VclException("MAPPING_RECEIPT_INTEGRITY", "");
        }
        return parser.readJson(snapshot.normalization().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Override
    @Transactional(readOnly = true)
    public VclSourceBindingDescription describe(UUID actorId, UUID sourceId, Long schemaRevision) {
        if (schemaRevision != null && schemaRevision < 1) throw new VclException("BINDING_REVISION_INVALID", "");
        Account account = account(actorId);
        Current current = current(account.id(), sourceId);
        if (!"ACTIVE".equals(current.deviceStatus())) throw new VclException("SOURCE_BINDING_UNAVAILABLE", "");
        Stored head = stored(sourceId, null);
        if (head == null || !definition(head).path("configurationDigest").asText().equals(current.configurationDigest())) {
            throw new VclException("BINDING_METADATA_STALE", "");
        }
        Stored selected = schemaRevision == null ? head : stored(sourceId, schemaRevision);
        if (selected == null) throw new VclException("SOURCE_BINDING_UNAVAILABLE", "");
        String state = state(current, head.revision());
        if (!account.scope().equals(account(actorId).scope())
                || !state.equals(state(current(account.id(), sourceId), head.revision()))) {
            throw new VclException("ACCESS_SCOPE_CHANGED", "");
        }
        boolean active = current.source().isEnabled() && current.source().getStatus() == SourceStatus.ACTIVE;
        return new VclSourceBindingDescription(sourceId, account.id(), actorId, current.source().getDeviceId(),
                selected.revision(), head.revision(), selected.snapshotId(), definition(selected), selected.digest(),
                account.scope(), state, active, false, SourceBindingDefinition.capabilities());
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.Optional<com.voltcanary.common.events.device.SourceBindingSchemaEvent> publication(UUID accountId, UUID sourceId) {
        Current current = current(accountId, sourceId);
        Stored head = stored(sourceId, null);
        if (head == null) return java.util.Optional.empty();
        if (!definition(head).path("configurationDigest").asText().equals(current.configurationDigest())) {
            throw new VclException("BINDING_METADATA_STALE", "");
        }
        var pin = new com.voltcanary.common.events.telemetry.SourceBindingSchemaPin(sourceId, accountId,
                current.source().getDeviceId(), current.source().getSourceId(), head.revision(), head.snapshotId(), head.digest());
        boolean active = "ACTIVE".equals(current.deviceStatus()) && current.source().isEnabled() && current.source().getStatus() == SourceStatus.ACTIVE;
        return java.util.Optional.of(new com.voltcanary.common.events.device.SourceBindingSchemaEvent(UUID.randomUUID(), "v1", pin,
                head.canonical(), active, java.time.Instant.now(), "device-service"));
    }

    private Account account(UUID actor) {
        return jdbc.query("SELECT id,updated_at FROM accounts WHERE user_id=? AND status='ACTIVE'", (row, index) -> {
            UUID accountId = row.getObject("id", UUID.class);
            return new Account(accountId, VclCanonical.sha256("NATIVE_OWNER/1:" + accountId + ":" + actor + ":" + row.getString("updated_at")));
        }, actor).stream().findFirst().orElseThrow(() -> new VclException("ACCOUNT_ACCESS_REVOKED", ""));
    }

    private Current current(UUID account, UUID sourceId) {
        SourceRow found = jdbc.query("""
                SELECT s.*,d.status device_status FROM data_sources s JOIN devices d ON d.id=s.device_id
                WHERE s.id=? AND d.account_id=?
                """, (row, index) -> {
            var value = new DataSource();
            value.setId(row.getObject("id", UUID.class));
            value.setDeviceId(row.getObject("device_id", UUID.class));
            value.setSourceId(row.getString("source_id"));
            value.setSourceType(DataSourceType.valueOf(row.getString("source_type")));
            value.setProtocol(DataSourceProtocol.valueOf(row.getString("protocol")));
            value.setStatus(SourceStatus.valueOf(row.getString("status")));
            value.setEnabled(row.getBoolean("enabled"));
            value.setMappingJson(row.getString("mapping_json"));
            value.setAdapterConfigJson(row.getString("adapter_config_json"));
            return new SourceRow(value, row.getString("device_status"));
        }, sourceId, account).stream().findFirst()
                .orElseThrow(() -> new VclException("SOURCE_BINDING_UNAVAILABLE", ""));
        String processing = jdbc.query("SELECT digest FROM source_processing_bundles WHERE data_source_id=? ORDER BY generation DESC LIMIT 1",
                (row, index) -> row.getString(1), sourceId).stream().findFirst().orElse("");
        return new Current(found.source(), found.deviceStatus(), SourceBindingDefinition.configurationDigest(found.source(), processing));
    }

    private Map<String, UUID> identities(UUID sourceId, java.util.List<String> keys) {
        var ids = new LinkedHashMap<String, UUID>();
        for (String key : keys) jdbc.query("SELECT field_id FROM cfg_source_field_identity WHERE source_id=? AND field_key=?",
                row -> { ids.put(key, row.getObject(1, UUID.class)); }, sourceId, key);
        return ids;
    }

    private Stored stored(UUID sourceId, Long revision) {
        String sql = """
                SELECT schema_revision,snapshot_id,definition_digest,canonical_definition,canonical_normalization,normalization_digest
                FROM cfg_source_binding_snapshot WHERE source_id=?
                """;
        Object[] arguments = revision == null ? new Object[]{sourceId} : new Object[]{sourceId, revision};
        sql += revision == null ? " ORDER BY schema_revision DESC LIMIT 1" : " AND schema_revision=?";
        return jdbc.query(sql, (row, index) -> new Stored(sourceId, row.getLong(1), row.getObject(2, UUID.class),
                row.getString(3), row.getString(4), row.getString(5), row.getString(6)), arguments)
                .stream().findFirst().orElse(null);
    }

    private JsonNode definition(Stored stored) {
        if (!VclCanonical.sha256(stored.canonical()).equals(stored.digest())) throw new VclException("BINDING_SNAPSHOT_INTEGRITY", "");
        JsonNode value = parser.readJson(stored.canonical().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if (!VclCanonical.encode(value).equals(stored.canonical()) || value.path("schemaRevision").asLong() != stored.revision()
                || !stored.sourceId().toString().equals(value.path("id").asText())) {
            throw new VclException("BINDING_SNAPSHOT_INTEGRITY", "");
        }
        return value;
    }

    private String state(Current current, long revision) {
        var source = current.source();
        return VclCanonical.sha256("SOURCE_STATE/1:" + source.getId() + ":" + revision + ":" + current.configurationDigest()
                + ":" + source.isEnabled() + ":" + source.getStatus() + ":" + current.deviceStatus());
    }
}
