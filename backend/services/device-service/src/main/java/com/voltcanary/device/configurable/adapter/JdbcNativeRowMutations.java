package com.voltcanary.device.configurable.adapter;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.voltcanary.device.configurable.domain.NativeRowReview;
import com.voltcanary.device.configurable.port.NativeRowMutations;
import com.voltcanary.vcl.VclCanonical;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import static com.voltcanary.device.configurable.adapter.NativeRowReviewRows.require;

/** Mutation, optional dense history, head CAS, exact receipt and outbox commit in one database transaction. */
public final class JdbcNativeRowMutations implements NativeRowMutations {
    private final DataSource source;
    private final Clock clock;
    public JdbcNativeRowMutations(DataSource source, Clock clock) {
        this.source = source;
        this.clock = clock;
    }

    @Override
    public NativeRowReview apply(UUID account, UUID actor, UUID id) {
        return NativeRowUnitOfWork.run(source, account, actor, (connection, scope) -> {
            var review = NativeRowReviewRows.locked(connection, account, actor, id);
            if (review.status().equals("SUCCEEDED")) return review;
            require(List.of("APPLYING", "UNKNOWN").contains(review.status()), "REVIEW_STATE_CONFLICT");
            require(clock.instant().isBefore(review.expiresAt()), "REVIEW_EXPIRED");
            require(scope.equals(review.scopeDigest()), "ACCESS_SCOPE_CHANGED");
            var dataset = NativeRowTransactions.head(connection, account, review.datasetId(), review.schemaRevision());
            var document = NativeMutationValidation.PARSER.parse(review.canonicalDocument());
            String canonical = NativeMutationValidation.compile(connection, dataset, scope, document, review.createdRecordId());
            require(canonical.equals(review.canonicalDocument()) && VclCanonical.sha256(canonical).equals(review.documentDigest()), "REVIEW_INTEGRITY");
            var result = NativeRowDml.mutate(connection, dataset, document.spec(), review.createdRecordId());
            var payload = JsonNodeFactory.instance.objectNode().put("mutationId", review.mutationId().toString()).put("datasetId", review.datasetId().toString())
                    .put("recordId", result.recordId().toString()).put("recordVersion", Long.toString(result.recordVersion()))
                    .put("dataVersion", result.dataVersion()).put("generation", dataset.generation().toString())
                    .put("schemaRevision", dataset.schema().revision()).put("deleted", result.deleted());
            String bytes = VclCanonical.encode(payload);
            JdbcNativeDatasetCatalog.execute(connection, """
                    INSERT INTO cfg_row_receipt(id,account_id,review_id,operation,idempotency_key,payload_digest,result_json)
                    VALUES(?,?,?,?,?,?,?)
                    """, review.receiptId(), account, id, document.spec().path("operation").asText(), review.reservationKey(), review.documentDigest(), bytes);
            JdbcNativeDatasetCatalog.execute(connection, "INSERT INTO cfg_outbox(id,account_id,aggregate_id,event_type,payload_json) VALUES(?,?,?,'NATIVE_ROW_MUTATED',?)",
                    review.receiptId(), account, review.datasetId(), bytes);
            JdbcNativeDatasetCatalog.execute(connection, "UPDATE cfg_row_review SET status='SUCCEEDED',outcome_code=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=?", id);
            return NativeRowReviewRows.locked(connection, account, actor, id);
        });
    }
}
