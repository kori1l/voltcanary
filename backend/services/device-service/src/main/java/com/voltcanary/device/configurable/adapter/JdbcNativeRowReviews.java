package com.voltcanary.device.configurable.adapter;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.voltcanary.device.configurable.domain.NativeRowReview;
import com.voltcanary.device.configurable.port.NativeRowReviews;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclDocument;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import static com.voltcanary.device.configurable.adapter.NativeRowReviewRows.require;

public final class JdbcNativeRowReviews implements NativeRowReviews {
    private final DataSource source;
    private final Clock clock;

    public JdbcNativeRowReviews(DataSource source, Clock clock) {
        this.source = source;
        this.clock = clock;
    }

    @Override
    public NativeRowReview reserve(UUID account, UUID actor, UUID dataset, long revision, String key) {
        return NativeRowUnitOfWork.run(source, account, actor, (connection, scope) -> {
            expire(connection, account);
            try (var query = connection.prepareStatement("SELECT id,actor_id,dataset_id,schema_revision FROM cfg_row_review WHERE account_id=? AND reservation_key=?")) {
                query.setObject(1, account);
                query.setString(2, key);
                try (var rows = query.executeQuery()) {
                    if (rows.next()) {
                        require(actor.equals(rows.getObject("actor_id", UUID.class)) && dataset.equals(rows.getObject("dataset_id", UUID.class))
                                && revision == rows.getLong("schema_revision"), "IDEMPOTENCY_KEY_REUSED");
                        return NativeRowReviewRows.locked(connection, account, actor, rows.getObject("id", UUID.class));
                    }
                }
            }
            NativeRowTransactions.head(connection, account, dataset, revision);
            try (var query = connection.prepareStatement("SELECT COUNT(*) FROM cfg_row_review WHERE account_id=? AND status IN ('RESERVED','PREPARED','APPLYING','UNKNOWN')")) {
                query.setObject(1, account);
                try (var rows = query.executeQuery()) {
                    rows.next();
                    require(rows.getLong(1) < 100, "REVIEW_QUOTA");
                }
            }
            UUID id = UUID.randomUUID();
            JdbcNativeDatasetCatalog.execute(connection, """
                    INSERT INTO cfg_row_review(id,account_id,actor_id,dataset_id,schema_revision,mutation_id,created_record_id,receipt_id,
                    reservation_key,status,revision,expires_at,scope_digest) VALUES(?,?,?,?,?,?,?,?,?,'RESERVED',1,?,?)
                    """, id, account, actor, dataset, revision, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), key,
                    OffsetDateTime.ofInstant(clock.instant().plus(Duration.ofMinutes(15)), ZoneOffset.UTC), scope);
            return NativeRowReviewRows.locked(connection, account, actor, id);
        });
    }

    @Override
    public NativeRowReview prepare(UUID account, UUID actor, UUID id, VclDocument document) {
        return NativeRowUnitOfWork.run(source, account, actor, (connection, scope) -> {
            var review = NativeRowReviewRows.locked(connection, account, actor, id);
            require(clock.instant().isBefore(review.expiresAt()) && !review.status().equals("EXPIRED"), "REVIEW_EXPIRED");
            require(List.of("RESERVED", "PREPARED").contains(review.status()), "REVIEW_STATE_CONFLICT");
            var spec = document.spec();
            require(document.kind() == VclDocument.Kind.MUTATION, "MUTATION_REQUIRED");
            require(spec.at("/target/datasetId").asText().equals(review.datasetId().toString())
                    && spec.at("/target/schemaRevision").asLong() == review.schemaRevision(), "RESERVED_RESOURCE_MISMATCH");
            require(spec.path("reviewId").asText().equals(id.toString()) && spec.path("mutationId").asText().equals(review.mutationId().toString()), "RESERVED_RESOURCE_MISMATCH");
            require(spec.path("idempotencyKey").asText().equals(review.reservationKey()), "IDEMPOTENCY_KEY_REUSED");
            var dataset = NativeRowTransactions.head(connection, account, review.datasetId(), review.schemaRevision());
            String canonical = NativeMutationValidation.compile(connection, dataset, scope, document, review.createdRecordId());
            long revision = review.canonicalDocument() == null || canonical.equals(review.canonicalDocument()) && scope.equals(review.scopeDigest())
                    ? review.revision() : Math.addExact(review.revision(), 1);
            String documentDigest = VclCanonical.sha256(canonical);
            String digest = VclCanonical.sha256(VclCanonical.encode(JsonNodeFactory.instance.objectNode().put("version", "native-row-review-1")
                    .put("reviewId", id.toString()).put("revision", revision).put("accountId", account.toString()).put("actorId", actor.toString())
                    .put("documentDigest", documentDigest).put("scopeDigest", scope).put("createdRecordId", review.createdRecordId().toString())
                    .put("receiptId", review.receiptId().toString()).put("expiresAt", review.expiresAt().toString())));
            String semantic = VclCanonical.semanticDigest(NativeMutationValidation.PARSER.parse(canonical));
            JdbcNativeDatasetCatalog.execute(connection, """
                    UPDATE cfg_row_review SET status='PREPARED',revision=?,canonical_document=?,document_digest=?,semantic_digest=?,
                    review_digest=?,scope_digest=?,updated_at=CURRENT_TIMESTAMP WHERE id=?
                    """, revision, canonical, documentDigest, semantic, digest, scope, id);
            return NativeRowReviewRows.locked(connection, account, actor, id);
        });
    }

    @Override
    public NativeRowReview begin(UUID account, UUID actor, UUID id, long revision, String digest, String key) {
        return NativeRowUnitOfWork.run(source, account, actor, (connection, scope) -> {
            var review = NativeRowReviewRows.locked(connection, account, actor, id);
            require(review.revision() == revision && digest.equals(review.reviewDigest()), "REVIEW_CONFLICT");
            require(key.equals(review.reservationKey()), "IDEMPOTENCY_KEY_REUSED");
            if (List.of("SUCCEEDED", "APPLYING", "UNKNOWN", "FAILED").contains(review.status())) return review;
            require(clock.instant().isBefore(review.expiresAt()), "REVIEW_EXPIRED");
            require(review.status().equals("PREPARED"), "REVIEW_STATE_CONFLICT");
            require(scope.equals(review.scopeDigest()), "ACCESS_SCOPE_CHANGED");
            JdbcNativeDatasetCatalog.execute(connection, "UPDATE cfg_row_review SET status='APPLYING',updated_at=CURRENT_TIMESTAMP WHERE id=?", id);
            return NativeRowReviewRows.locked(connection, account, actor, id);
        });
    }

    @Override
    public NativeRowReview inspect(UUID account, UUID actor, UUID id) {
        return NativeRowUnitOfWork.run(source, account, actor, (connection, scope) -> {
            expire(connection, account);
            return NativeRowReviewRows.locked(connection, account, actor, id);
        });
    }

    @Override
    public NativeRowReview outcome(UUID account, UUID actor, UUID id, String status, String code) {
        require(List.of("UNKNOWN", "FAILED").contains(status) && code.matches("[A-Z][A-Z0-9_]{0,63}"), "INVALID_DIAGNOSTIC");
        return NativeRowUnitOfWork.run(source, account, actor, (connection, scope) -> {
            var review = NativeRowReviewRows.locked(connection, account, actor, id);
            if (List.of("SUCCEEDED", "FAILED").contains(review.status())) return review;
            require(List.of("APPLYING", "UNKNOWN").contains(review.status()), "REVIEW_STATE_CONFLICT");
            JdbcNativeDatasetCatalog.execute(connection, "UPDATE cfg_row_review SET status=?,outcome_code=?,updated_at=CURRENT_TIMESTAMP WHERE id=?", status, code, id);
            return NativeRowReviewRows.locked(connection, account, actor, id);
        });
    }

    private void expire(Connection connection, UUID account) throws SQLException {
        JdbcNativeDatasetCatalog.execute(connection, "UPDATE cfg_row_review SET status='EXPIRED',updated_at=CURRENT_TIMESTAMP WHERE account_id=? AND status IN ('RESERVED','PREPARED') AND expires_at<=?",
                account, OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
    }
}
