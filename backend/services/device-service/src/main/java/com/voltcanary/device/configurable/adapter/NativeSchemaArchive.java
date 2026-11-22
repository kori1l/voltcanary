package com.voltcanary.device.configurable.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.voltcanary.device.configurable.port.NativeMigrationDependencies;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclException;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;

final class NativeSchemaArchive {

    private NativeSchemaArchive() {}

    static JsonNode apply(
        DataSource source,
        Clock clock,
        NativeMigrationDependencies dependencies,
        UUID account,
        UUID actor,
        UUID dataset,
        UUID review,
        long revision,
        String digest,
        String key
    ) {
        var initial = NativeRowUnitOfWork.run(source, account, actor, (connection, scope) -> {
            var saved = NativeSchemaMigrationReviews.load(
                connection,
                account,
                actor,
                dataset,
                review
            );
            confirmation(saved, revision, digest, key);
            return saved;
        });
        if (
            initial.path("status").asText().equals("SUCCEEDED")
        ) return NativeSchemaMigrationReviews.view(initial);
        var owners = dependencies.inspect(
            account,
            actor,
            dataset,
            initial.path("expected_schema_revision").longValue()
        );
        return NativeRowUnitOfWork.run(source, account, actor, (connection, scope) -> {
            var saved = NativeSchemaMigrationReviews.load(
                connection,
                account,
                actor,
                dataset,
                review
            );
            confirmation(saved, revision, digest, key);
            if (
                saved.path("status").asText().equals("SUCCEEDED")
            ) return NativeSchemaMigrationReviews.view(saved);
            require(saved.path("status").asText().equals("PREPARED"), "REVIEW_STATE_CONFLICT");
            NativeSchemaMigrationReviews.usable(saved, scope, clock.instant());
            var previous = NativeSchemaMigrationReviews.head(connection, account, dataset, saved);
            var impact =
                (com.fasterxml.jackson.databind.node.ObjectNode) NativeMigrationImpact.inspect(
                    connection,
                    previous,
                    null,
                    owners,
                    clock.instant()
                );
            var reviewed = NativeImportPlans.payload(saved.path("impact_json").asText(), false);
            for (String field : List.of("sourceRows", "validRows", "dataMode", "errors"))
                impact.set(field, reviewed.path(field));
            require(
                !impact.path("blocked").asBoolean() &&
                    VclCanonical.encode(impact).equals(saved.path("impact_json").asText()),
                "VALIDATION_STALE"
            );
            // Account and head fences are shared with reference writers and import dispatch.
            NativeHistoryCapture.retire(connection, previous);
            JdbcNativeDatasetCatalog.execute(
                connection,
                "UPDATE cfg_dataset SET status='ARCHIVED',updated_at=CURRENT_TIMESTAMP WHERE id=? AND account_id=? AND status='READY'",
                dataset,
                account
            );
            JdbcNativeDatasetCatalog.execute(
                connection,
                "UPDATE cfg_native_generation SET status='ARCHIVED' WHERE dataset_id=? AND account_id=?",
                dataset,
                account
            );
            JdbcNativeDatasetCatalog.execute(
                connection,
                "UPDATE cfg_import_job SET status='CANCELLED' WHERE dataset_id=? AND account_id=? AND status='PREPARED'",
                dataset,
                account
            );
            JdbcNativeDatasetCatalog.execute(
                connection,
                "UPDATE cfg_row_review SET status='CANCELLED',updated_at=CURRENT_TIMESTAMP WHERE dataset_id=? AND account_id=? AND status IN ('RESERVED','PREPARED','APPLYING','UNKNOWN')",
                dataset,
                account
            );
            JdbcNativeDatasetCatalog.execute(
                connection,
                "UPDATE cfg_native_schema_migration SET status='CANCELLED',outcome_code='DATASET_ARCHIVED' WHERE dataset_id=? AND account_id=? AND id<>? AND status IN ('RESERVED','PREPARED','APPLYING','UNKNOWN')",
                dataset,
                account,
                review
            );
            var receipt = NativeSchemaMigrationReviews.receipt(saved);
            String canonical = VclCanonical.encode(receipt);
            JdbcNativeDatasetCatalog.execute(
                connection,
                "UPDATE cfg_native_schema_migration SET status='SUCCEEDED',result_json=?,outcome_code=NULL WHERE id=?",
                canonical,
                review
            );
            JdbcNativeDatasetCatalog.execute(
                connection,
                "INSERT INTO cfg_outbox(id,account_id,aggregate_id,event_type,payload_json) VALUES(?,?,?,'NATIVE_DATASET_ARCHIVED',?)",
                UUID.fromString(saved.path("receipt_id").asText()),
                account,
                dataset,
                canonical
            );
            return NativeSchemaMigrationReviews.view(
                NativeSchemaMigrationReviews.load(connection, account, actor, dataset, review)
            );
        });
    }

    private static void confirmation(JsonNode saved, long revision, String digest, String key) {
        NativeSchemaMigrationReviews.verify(saved);
        require(
            revision == 2 &&
                revision == saved.path("revision").longValue() &&
                digest != null &&
                digest.equals(saved.path("review_digest").asText()),
            "REVIEW_CONFLICT"
        );
        require(
            key != null && key.equals(saved.path("reservation_key").asText()),
            "IDEMPOTENCY_KEY_REUSED"
        );
        var request = NativeImportPlans.payload(saved.path("canonical_request").asText(), false);
        require(
            request.path("dataMode").asText().equals("ARCHIVE") && request.path("schema").isNull(),
            "REVIEW_STATE_CONFLICT"
        );
    }

    private static void require(boolean condition, String code) {
        if (!condition) throw new VclException(code, "");
    }
}
