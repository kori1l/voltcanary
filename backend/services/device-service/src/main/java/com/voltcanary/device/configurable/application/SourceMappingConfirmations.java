package com.voltcanary.device.configurable.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voltcanary.device.configurable.port.SourceBindingCatalog;
import com.voltcanary.device.event.SourceRuntimeConfigEventPublisher;
import com.voltcanary.device.repository.DataSourceRepository;
import com.voltcanary.device.security.AuthenticatedUser;
import com.voltcanary.device.variable.application.VariableRetentionGuard;
import com.voltcanary.vcl.VclBooleanTokens;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclException;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Confirms an exact preview under the source lock and publishes its immutable schema revision. */
@Service
@RequiredArgsConstructor
public class SourceMappingConfirmations {

    private final SourceBindingCatalog bindings;
    private final DataSourceRepository sources;
    private final SourceRuntimeConfigEventPublisher publisher;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final VariableRetentionGuard retention;
    private final SourceMappingHistory history;

    public record Receipt(
        UUID bindingId,
        UUID deviceId,
        UUID fieldId,
        String mappingDigest,
        long schemaRevision,
        UUID schemaSnapshotId,
        String definitionDigest
    ) {}

    @Transactional(readOnly = true)
    public Receipt receipt(AuthenticatedUser actor, UUID id, long revision, String digest) {
        var base = bindings.describe(actor.id(), id, revision);
        var receipt = findReceipt(base, digest);
        if (receipt == null) throw new VclException("MAPPING_RECEIPT_UNAVAILABLE", "");
        return receipt;
    }

    private Receipt findReceipt(
        com.voltcanary.vcl.VclSourceBindingDescription base,
        String digest
    ) {
        var matches = jdbc.query(
            """
            SELECT device_id,field_id,applied_revision,snapshot_id,definition_digest FROM cfg_source_mapping_receipt
            WHERE source_id=? AND account_id=? AND review_digest=? AND base_revision=? AND review_scope_digest=?
            """,
            (row, index) ->
                new Receipt(
                    base.id(),
                    row.getObject(1, UUID.class),
                    row.getObject(2, UUID.class),
                    digest,
                    row.getLong(3),
                    row.getObject(4, UUID.class),
                    row.getString(5)
                ),
            base.id(),
            base.accountId(),
            digest,
            base.schemaRevision(),
            base.accessScopeDigest()
        );
        return matches.isEmpty() ? null : matches.getFirst();
    }

    @Transactional
    public Receipt confirm(
        AuthenticatedUser actor,
        UUID id,
        long revision,
        UUID fieldId,
        JsonNode conversion,
        String digest
    ) {
        VclBooleanTokens.parse(conversion, "/conversion");
        if (
            jdbc
                .queryForList(
                    "SELECT id FROM accounts WHERE user_id=? AND status='ACTIVE' FOR UPDATE",
                    UUID.class,
                    actor.id()
                )
                .isEmpty()
        ) {
            throw new VclException("ACCOUNT_ACCESS_REVOKED", "");
        }
        var initial = bindings.describe(actor.id(), id, revision);
        if (
            jdbc
                .queryForList(
                    """
                    SELECT s.id FROM data_sources s JOIN devices d ON s.device_id=d.id
                    WHERE s.id=? AND d.account_id=? FOR UPDATE
                    """,
                    UUID.class,
                    id,
                    initial.accountId()
                )
                .isEmpty()
        ) throw new VclException("SOURCE_BINDING_UNAVAILABLE", "");
        var base = bindings.describe(actor.id(), id, revision);
        if (!base.sourceActive()) throw new VclException("SOURCE_NOT_ACTIVE", "");
        if (!SourceMappingPreviews.digest(base, fieldId, conversion).equals(digest)) {
            throw new VclException("MAPPING_REVIEW_CONFLICT", "");
        }
        var existing = findReceipt(base, digest);
        if (existing != null) return existing;
        if (base.currentSchemaRevision() != revision) throw new VclException(
            "BINDING_REVISION_CHANGED",
            ""
        );
        String key = fieldKey(base.definition(), fieldId);
        var source = sources
            .findById(id)
            .orElseThrow(() -> new VclException("SOURCE_BINDING_UNAVAILABLE", ""));
        ObjectNode mapping;
        try {
            mapping = (ObjectNode) mapper.readTree(source.getMappingJson());
        } catch (java.io.IOException | ClassCastException failure) {
            throw new VclException("BINDING_CONFIGURATION_INVALID", "");
        }
        boolean changed = false;
        var previous = history.conversion(base, fieldId);
        for (var field : mapping.at("/telemetryPolicy/fields")) {
            if (!key.equals(field.path("key").textValue())) continue;
            String type = field.path("type").textValue();
            if (previous.isPresent()) {
                if (
                    !"boolean".equals(type) ||
                    !previous.get().equals(mapping.path("reviewedBooleanMappings").path(key))
                ) {
                    throw new VclException("MAPPING_RECEIPT_INTEGRITY", "");
                }
                if (previous.get().equals(conversion)) throw new VclException(
                    "MAPPING_UNCHANGED",
                    ""
                );
            } else if (!"string".equals(type)) {
                throw new VclException("CONVERSION_TYPE_MISMATCH", "");
            }
            ((ObjectNode) field).put("type", "boolean");
            changed = true;
        }
        if (!changed) throw new VclException("MAPPING_FIELD_UNAVAILABLE", "");
        mapping.withObjectProperty("reviewedBooleanMappings").set(key, conversion.deepCopy());
        retention.check(
            id,
            mapper.convertValue(
                mapping,
                new com.fasterxml.jackson.core.type.TypeReference<
                    java.util.Map<String, Object>
                >() {}
            )
        );
        source.setMappingJson(mapping.toString());
        Instant now = Instant.now();
        Instant after = source.getUpdatedAt().plusNanos(1000);
        source.setUpdatedAt(now.isAfter(after) ? now : after);
        publisher.publish(base.accountId(), source);
        var applied = bindings.describe(actor.id(), id, null);
        jdbc.update(
            """
            INSERT INTO cfg_source_mapping_receipt(source_id,review_digest,account_id,device_id,field_id,
                base_revision,applied_revision,snapshot_id,definition_digest,canonical_conversion,review_scope_digest)
            VALUES(?,?,?,?,?,?,?,?,?,?,?)
            """,
            id,
            digest,
            base.accountId(),
            applied.deviceId(),
            fieldId,
            revision,
            applied.schemaRevision(),
            applied.snapshotId(),
            applied.definitionDigest(),
            VclCanonical.encode(conversion),
            base.accessScopeDigest()
        );
        return new Receipt(
            id,
            applied.deviceId(),
            fieldId,
            digest,
            applied.schemaRevision(),
            applied.snapshotId(),
            applied.definitionDigest()
        );
    }

    private static String fieldKey(JsonNode definition, UUID id) {
        for (var mapping : definition.path("fieldMappings")) {
            if (
                id.toString().equals(mapping.path("fieldId").textValue()) &&
                mapping.path("vclReady").booleanValue()
            ) {
                return mapping.path("sourceKey").textValue();
            }
        }
        throw new VclException("MAPPING_FIELD_UNAVAILABLE", "");
    }
}
