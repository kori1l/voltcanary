package com.voltcanary.device.configurable.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.voltcanary.device.configurable.port.NativeMigrationDependencies;
import com.voltcanary.device.configurable.port.NativeSchemaMigrations;
import com.voltcanary.vcl.VclException;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;

/** Owner adapter shared by manual and assistant callers; callers cannot supply SQL or owner evidence. */
public final class JdbcNativeSchemaMigrations implements NativeSchemaMigrations {

    private final DataSource source;
    private final Clock clock;
    private final NativeMigrationDependencies dependencies;
    private final NativeSchemaMigrationReviews reviews;
    private final NativeSchemaMigrationStages stages;

    public JdbcNativeSchemaMigrations(
        DataSource source,
        Clock clock,
        NativeMigrationDependencies dependencies
    ) {
        this.source = source;
        this.clock = clock;
        this.dependencies = dependencies;
        reviews = new NativeSchemaMigrationReviews(source, clock, dependencies);
        stages = new NativeSchemaMigrationStages(source, clock, dependencies);
    }

    @Override
    public JsonNode reserve(
        UUID account,
        UUID actor,
        UUID dataset,
        String key,
        List<String> fieldKeys
    ) {
        return reviews.reserve(account, actor, dataset, key, fieldKeys);
    }

    @Override
    public JsonNode prepare(UUID account, UUID actor, UUID dataset, UUID review, JsonNode request) {
        return reviews.prepare(account, actor, dataset, review, request);
    }

    @Override
    public JsonNode inspect(UUID account, UUID actor, UUID dataset, UUID review) {
        return reviews.inspect(account, actor, dataset, review);
    }

    @Override
    public JsonNode apply(
        UUID account,
        UUID actor,
        UUID dataset,
        UUID review,
        long revision,
        String digest,
        String key
    ) {
        boolean archive = NativeRowUnitOfWork.run(source, account, actor, (connection, scope) -> {
            var saved = NativeSchemaMigrationReviews.load(
                connection,
                account,
                actor,
                dataset,
                review
            );
            NativeSchemaMigrationReviews.verify(saved);
            return NativeImportPlans.payload(saved.path("canonical_request").asText(), false)
                .path("dataMode")
                .asText()
                .equals("ARCHIVE");
        });
        try {
            return archive
                ? NativeSchemaArchive.apply(
                      source,
                      clock,
                      dependencies,
                      account,
                      actor,
                      dataset,
                      review,
                      revision,
                      digest,
                      key
                  )
                : stages.apply(account, actor, dataset, review, revision, digest, key);
        } catch (VclException failure) {
            return recover(account, actor, dataset, review, revision, digest, key, failure);
        }
    }

    private JsonNode recover(
        UUID account,
        UUID actor,
        UUID dataset,
        UUID review,
        long revision,
        String digest,
        String key,
        VclException failure
    ) {
        var completed = NativeRowUnitOfWork.run(source, account, actor, (connection, scope) -> {
            var saved = NativeSchemaMigrationReviews.load(
                connection,
                account,
                actor,
                dataset,
                review
            );
            boolean exact =
                revision == saved.path("revision").longValue() &&
                digest != null &&
                key != null &&
                digest.equals(saved.path("review_digest").asText()) &&
                key.equals(saved.path("reservation_key").asText());
            if (!exact) return null;
            NativeSchemaMigrationReviews.verify(saved);
            if (
                saved.path("status").asText().equals("SUCCEEDED")
            ) return NativeSchemaMigrationReviews.view(saved);
            if (Set.of("VALIDATION_STALE", "REVIEW_EXPIRED").contains(failure.code())) {
                JdbcNativeDatasetCatalog.execute(
                    connection,
                    """
                    UPDATE cfg_native_schema_migration SET status='FAILED',outcome_code=?
                    WHERE id=? AND status IN ('PREPARED','APPLYING','UNKNOWN')
                    """,
                    failure.code(),
                    review
                );
                JdbcNativeDatasetCatalog.execute(
                    connection,
                    """
                    UPDATE cfg_native_generation SET status='ARCHIVED'
                    WHERE dataset_id=? AND account_id=? AND migration_review_id=? AND status IN ('PROVISIONING','READY')
                    """,
                    dataset,
                    account,
                    review
                );
            }
            return null;
        });
        if (completed != null) return completed;
        throw failure;
    }

    @Override
    public JsonNode archive(UUID account, UUID actor, UUID dataset, JsonNode request) {
        if (
            request == null ||
            !request.isObject() ||
            request.size() != 4 ||
            !Set.of("reviewId", "reviewRevision", "reviewDigest", "idempotencyKey")
                .stream()
                .allMatch(request::has) ||
            !request.path("reviewRevision").isIntegralNumber() ||
            !request.path("reviewRevision").canConvertToLong()
        ) {
            throw new VclException("REQUEST_FIELDS_INVALID", "");
        }
        UUID id;
        try {
            id = UUID.fromString(request.path("reviewId").asText());
        } catch (IllegalArgumentException invalid) {
            throw new VclException("RESERVATION_ID_INVALID", "");
        }
        return NativeSchemaArchive.apply(
            source,
            clock,
            dependencies,
            account,
            actor,
            dataset,
            id,
            request.path("reviewRevision").longValue(),
            request.path("reviewDigest").asText(),
            request.path("idempotencyKey").asText()
        );
    }
}
