package com.voltcanary.device.configurable.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.device.configurable.domain.NativeCollectionReview;
import com.voltcanary.device.configurable.domain.NativeDataset;
import com.voltcanary.device.configurable.domain.NativeSchema;
import com.voltcanary.device.configurable.port.NativeCollectionReviewStore;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;

/** Owns short local review transactions; DDL dispatch is a separate durable phase. */
@RequiredArgsConstructor
public final class JdbcCollectionReviews implements NativeCollectionReviewStore {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final DataSource source;
    private final Clock clock;

    @Override
    public NativeCollectionReview reserve(UUID account, UUID actor, List<String> keys, String key) {
        var canonicalKeys = keys.stream().sorted().toList();
        var input = MAPPER.createObjectNode();
        var array = input.putArray("fieldKeys");
        canonicalKeys.forEach(array::add);
        String digest = VclCanonical.sha256(VclCanonical.encode(input));
        return transaction(account, actor, connection -> {
            expire(connection, account);
            try (var query = connection.prepareStatement("SELECT * FROM cfg_collection_review WHERE account_id=? AND reservation_key=?")) {
                query.setObject(1, account);
                query.setString(2, key);
                try (var rows = query.executeQuery()) {
                    if (rows.next()) {
                        var existing = read(rows);
                        require(existing.actorId().equals(actor) && existing.reservationDigest().equals(digest), "IDEMPOTENCY_KEY_REUSED");
                        return existing;
                    }
                }
            }
            try (var query = connection.prepareStatement("SELECT COUNT(*) FROM cfg_collection_review WHERE account_id=? AND status IN ('RESERVED','PREPARED','APPLYING','UNKNOWN')")) {
                query.setObject(1, account);
                try (var rows = query.executeQuery()) { rows.next(); require(rows.getLong(1) < 100, "REVIEW_QUOTA"); }
            }
            UUID id = UUID.randomUUID();
            var fields = MAPPER.createObjectNode();
            canonicalKeys.forEach(name -> fields.put(name, UUID.randomUUID().toString()));
            JdbcNativeDatasetCatalog.execute(connection, """
                    INSERT INTO cfg_collection_review(id,account_id,actor_id,resource_id,generation,record_id_field_id,receipt_id,
                    field_ids_json,reservation_key,reservation_digest,status,revision,expires_at,scope_digest)
                    VALUES(?,?,?,?,?,?,?,?,?,?,'RESERVED',1,?,?)
                    """, id, account, actor, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    VclCanonical.encode(fields), key, digest, OffsetDateTime.ofInstant(clock.instant().plus(Duration.ofMinutes(15)), ZoneOffset.UTC), scope(connection, account, actor));
            return locked(connection, account, actor, id);
        });
    }

    @Override
    public NativeCollectionReview inspect(UUID account, UUID actor, UUID id) {
        return transaction(account, actor, connection -> { expire(connection, account); return locked(connection, account, actor, id); });
    }

    @Override
    public NativeCollectionReview prepare(UUID account, UUID actor, UUID id, NativeSchema schema) {
        // Construct the closed layout before storing a review that could dispatch unsupported DDL.
        new NativeSqlLayout(schema);
        return transaction(account, actor, connection -> {
            var review = locked(connection, account, actor, id);
            require(!review.status().equals("EXPIRED"), "REVIEW_EXPIRED");
            require(List.of("RESERVED", "PREPARED").contains(review.status()), "REVIEW_STATE_CONFLICT");
            require(clock.instant().isBefore(review.expiresAt()), "REVIEW_EXPIRED");
            require(review.resourceId().equals(schema.id()), "RESERVED_RESOURCE_MISMATCH");
            var fieldIds = schema.fields().stream().map(field -> UUID.fromString(field.path("id").asText())).collect(java.util.stream.Collectors.toSet());
            require(fieldIds.equals(java.util.Set.copyOf(review.fieldIdsByKey().values())), "RESERVED_FIELDS_MISMATCH");
            String scope = scope(connection, account, actor);
            long revision = review.canonicalDocument() == null || schema.canonical().equals(review.canonicalDocument()) && scope.equals(review.scopeDigest())
                    ? review.revision() : Math.addExact(review.revision(), 1);
            var envelope = MAPPER.createObjectNode().put("version", "native-collection-review-1").put("reviewId", id.toString())
                    .put("actorId", actor.toString()).put("accountId", account.toString()).put("revision", revision)
                    .put("documentDigest", schema.digest()).put("scopeDigest", scope).put("generation", review.generation().toString())
                    .put("recordIdFieldId", review.recordIdFieldId().toString()).put("receiptId", review.receiptId().toString())
                    .put("expiresAt", review.expiresAt().toString());
            String reviewDigest = VclCanonical.sha256(VclCanonical.encode(envelope));
            String semanticDigest = VclCanonical.semanticDigest(new com.voltcanary.vcl.VclParser().parse(schema.canonical()));
            JdbcNativeDatasetCatalog.execute(connection, """
                    UPDATE cfg_collection_review SET status='PREPARED',revision=?,canonical_document=?,semantic_digest=?,
                    review_digest=?,scope_digest=?,updated_at=CURRENT_TIMESTAMP WHERE id=?
                    """, revision, schema.canonical(), semanticDigest, reviewDigest, scope, id);
            return locked(connection, account, actor, id);
        });
    }

    @Override
    public NativeCollectionReview begin(UUID account, UUID actor, UUID id, long revision, String digest, String key) {
        return transaction(account, actor, connection -> {
            var review = locked(connection, account, actor, id);
            require(!review.status().equals("EXPIRED"), "REVIEW_EXPIRED");
            require(review.revision() == revision && digest.equals(review.reviewDigest()), "REVIEW_CONFLICT");
            if (List.of("APPLYING", "UNKNOWN", "SUCCEEDED").contains(review.status())) {
                require(key.equals(review.applyKey()), "IDEMPOTENCY_KEY_REUSED");
                return review;
            }
            require(scope(connection, account, actor).equals(review.scopeDigest()), "ACCESS_SCOPE_CHANGED");
            require(review.status().equals("PREPARED"), "REVIEW_STATE_CONFLICT");
            require(clock.instant().isBefore(review.expiresAt()), "REVIEW_EXPIRED");
            JdbcNativeDatasetCatalog.execute(connection, "UPDATE cfg_collection_review SET status='APPLYING',apply_key=?,updated_at=CURRENT_TIMESTAMP WHERE id=?", key, id);
            JdbcNativeDatasetCatalog.execute(connection, """
                    INSERT INTO cfg_mutation_receipt(id,account_id,review_id,operation,idempotency_key,payload_digest,status)
                    VALUES(?,?,?,'CREATE_COLLECTION',?,?,'UNKNOWN')
                    """, review.receiptId(), account, id, key, digest);
            return locked(connection, account, actor, id);
        });
    }

    @Override
    public NativeCollectionReview complete(UUID account, UUID actor, UUID id, NativeDataset dataset) {
        return transaction(account, actor, connection -> {
            var review = locked(connection, account, actor, id);
            if (review.status().equals("SUCCEEDED")) return review;
            require(List.of("APPLYING", "UNKNOWN").contains(review.status()), "REVIEW_STATE_CONFLICT");
            require(dataset.accountId().equals(account) && dataset.schema().id().equals(review.resourceId())
                    && dataset.generation().equals(review.generation()) && dataset.recordIdFieldId().equals(review.recordIdFieldId())
                    && dataset.schema().canonical().equals(review.canonicalDocument()), "TARGET_CONFLICT");
            var result = MAPPER.createObjectNode().put("datasetId", dataset.schema().id().toString())
                    .put("schemaRevision", dataset.schema().revision()).put("dataVersion", dataset.dataVersion())
                    .put("generation", dataset.generation().toString()).put("recordIdFieldId", dataset.recordIdFieldId().toString());
            String payload = VclCanonical.encode(result);
            JdbcNativeDatasetCatalog.execute(connection, "UPDATE cfg_mutation_receipt SET status='SUCCEEDED',result_json=?,updated_at=CURRENT_TIMESTAMP WHERE review_id=?", payload, id);
            JdbcNativeDatasetCatalog.execute(connection, "UPDATE cfg_collection_review SET status='SUCCEEDED',outcome_code=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=?", id);
            JdbcNativeDatasetCatalog.execute(connection, "INSERT INTO cfg_outbox(id,account_id,aggregate_id,event_type,payload_json) VALUES(?,?,?,'COLLECTION_READY',?)",
                    review.receiptId(), account, review.resourceId(), payload);
            return locked(connection, account, actor, id);
        });
    }

    @Override
    public NativeCollectionReview unknown(UUID account, UUID actor, UUID id, String code) {
        require(code != null && code.matches("[A-Z][A-Z0-9_]{0,63}"), "INVALID_DIAGNOSTIC");
        return transaction(account, actor, connection -> {
            var review = locked(connection, account, actor, id);
            if (review.status().equals("SUCCEEDED")) return review;
            require(List.of("APPLYING", "UNKNOWN").contains(review.status()), "REVIEW_STATE_CONFLICT");
            JdbcNativeDatasetCatalog.execute(connection, "UPDATE cfg_collection_review SET status='UNKNOWN',outcome_code=?,updated_at=CURRENT_TIMESTAMP WHERE id=?", code, id);
            return locked(connection, account, actor, id);
        });
    }

    private NativeCollectionReview transaction(UUID account, UUID actor, Operation operation) {
        try (var connection = source.getConnection()) {
            connection.setAutoCommit(false);
            try {
                JdbcNativeDatasetCatalog.timeouts(connection);
                try (var lock = connection.prepareStatement("SELECT id FROM accounts WHERE id=? AND user_id=? AND status='ACTIVE' FOR UPDATE")) {
                    lock.setObject(1, account);
                    lock.setObject(2, actor);
                    try (var rows = lock.executeQuery()) { require(rows.next(), "ACCOUNT_ACCESS_REVOKED"); }
                }
                var result = operation.run(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                throw error;
            }
        } catch (SQLException error) {
            throw new VclException("23505".equals(error.getSQLState()) ? "IDEMPOTENCY_KEY_REUSED" : "NATIVE_STORAGE_UNAVAILABLE", "");
        }
    }

    private void expire(Connection connection, UUID account) throws SQLException {
        JdbcNativeDatasetCatalog.execute(connection, """
                UPDATE cfg_collection_review SET status='EXPIRED',updated_at=CURRENT_TIMESTAMP
                WHERE account_id=? AND status IN ('RESERVED','PREPARED') AND expires_at<=?
                """, account, OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
    }

    private static String scope(Connection connection, UUID account, UUID actor) throws SQLException {
        try (var query = connection.prepareStatement("SELECT updated_at FROM accounts WHERE id=? AND user_id=? AND status='ACTIVE'")) {
            query.setObject(1, account);
            query.setObject(2, actor);
            try (var rows = query.executeQuery()) {
                require(rows.next(), "ACCOUNT_ACCESS_REVOKED");
                return VclCanonical.sha256("NATIVE_OWNER/1:" + account + ":" + actor + ":" + rows.getString(1));
            }
        }
    }

    private static NativeCollectionReview locked(Connection connection, UUID account, UUID actor, UUID id) throws SQLException {
        try (var query = connection.prepareStatement("SELECT * FROM cfg_collection_review WHERE id=? AND account_id=? AND actor_id=? FOR UPDATE")) {
            query.setObject(1, id);
            query.setObject(2, account);
            query.setObject(3, actor);
            try (var rows = query.executeQuery()) { require(rows.next(), "REVIEW_UNAVAILABLE"); return read(rows); }
        }
    }

    private static NativeCollectionReview read(ResultSet row) throws SQLException {
        Map<String, UUID> fields = new LinkedHashMap<>();
        try { MAPPER.readTree(row.getString("field_ids_json")).fields().forEachRemaining(entry -> fields.put(entry.getKey(), UUID.fromString(entry.getValue().asText()))); }
        catch (java.io.IOException | IllegalArgumentException error) { throw new VclException("REVIEW_INTEGRITY", ""); }
        return new NativeCollectionReview(row.getObject("id", UUID.class), row.getObject("account_id", UUID.class), row.getObject("actor_id", UUID.class),
                row.getObject("resource_id", UUID.class), row.getObject("generation", UUID.class), row.getObject("record_id_field_id", UUID.class),
                row.getObject("receipt_id", UUID.class), fields, row.getString("reservation_key"), row.getString("reservation_digest"), row.getString("status"),
                row.getLong("revision"), row.getObject("expires_at", OffsetDateTime.class).toInstant(), row.getString("canonical_document"),
                row.getString("semantic_digest"), row.getString("review_digest"), row.getString("scope_digest"), row.getString("apply_key"), row.getString("outcome_code"));
    }

    private static void require(boolean condition, String code) { if (!condition) throw new VclException(code, ""); }
    @FunctionalInterface private interface Operation { NativeCollectionReview run(Connection connection) throws SQLException; }
}
