package com.voltcanary.device.configurable.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.voltcanary.device.configurable.port.SourceBindingCatalog;
import com.voltcanary.vcl.VclBooleanTokens;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclParser;
import com.voltcanary.vcl.VclSourceBindingDescription;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
final class SourceMappingHistory {

    private static final VclParser PARSER = new VclParser();
    private final SourceBindingCatalog bindings;
    private final JdbcTemplate jdbc;

    SourceMappingHistory(SourceBindingCatalog bindings, JdbcTemplate jdbc) {
        this.bindings = bindings;
        this.jdbc = jdbc;
    }

    private record Revision(
        long base,
        long applied,
        UUID snapshot,
        String definitionDigest,
        String reviewDigest,
        String conversion,
        String reviewScope
    ) {}

    Optional<JsonNode> conversion(VclSourceBindingDescription source, UUID field) {
        var revisions = jdbc.query(
            """
            SELECT base_revision,applied_revision,snapshot_id,definition_digest,review_digest,canonical_conversion,review_scope_digest
            FROM cfg_source_mapping_receipt WHERE source_id=? AND account_id=? AND device_id=? AND field_id=?
                AND applied_revision<=?
            ORDER BY applied_revision DESC LIMIT 1
            """,
            (row, index) ->
                new Revision(
                    row.getLong(1),
                    row.getLong(2),
                    row.getObject(3, UUID.class),
                    row.getString(4),
                    row.getString(5),
                    row.getString(6),
                    row.getString(7)
                ),
            source.id(),
            source.accountId(),
            source.deviceId(),
            field,
            source.schemaRevision()
        );
        if (revisions.isEmpty()) return Optional.empty();
        var revision = revisions.getFirst();
        var applied = bindings.describe(source.actorId(), source.id(), revision.applied());
        if (
            !revision.snapshot().equals(applied.snapshotId()) ||
            !revision.definitionDigest().equals(applied.definitionDigest())
        ) {
            throw new VclException("MAPPING_RECEIPT_INTEGRITY", "");
        }
        var conversion = PARSER.readJson(revision.conversion().getBytes(StandardCharsets.UTF_8));
        var base = bindings.describe(source.actorId(), source.id(), revision.base());
        String scope =
            revision.reviewScope() == null ? source.accessScopeDigest() : revision.reviewScope();
        if (
            !VclCanonical.encode(conversion).equals(revision.conversion()) ||
            !SourceMappingPreviews.digest(base.definitionDigest(), scope, field, conversion).equals(
                revision.reviewDigest()
            )
        ) {
            throw new VclException("MAPPING_RECEIPT_INTEGRITY", "");
        }
        VclBooleanTokens.parse(conversion, "/conversion");
        return Optional.of(conversion);
    }
}
