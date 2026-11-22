package com.voltcanary.device.configurable.adapter;

import static com.voltcanary.device.configurable.adapter.NativeRowReviewRows.require;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.voltcanary.device.configurable.domain.NativeRowReview;
import com.voltcanary.device.configurable.port.NativeRowMutations;
import com.voltcanary.vcl.VclCanonical;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;

/** Mutation, optional dense history, head CAS, exact receipt and outbox commit in one database transaction. */
public final class JdbcNativeRowMutations implements NativeRowMutations {

    private final NativeDataTransactions transactions;
    private final Clock clock;

    public JdbcNativeRowMutations(DataSource source, Clock clock) {
        this(clock, NativeDataTransactions.owner(source));
    }

    JdbcNativeRowMutations(Clock clock, NativeDataTransactions transactions) {
        this.transactions = transactions;
        this.clock = clock;
    }

    @Override
    public NativeRowReview apply(UUID account, UUID actor, UUID id) {
        return transactions.run(account, actor, (connection, access) -> {
            String scope = access.digest();
            NativeSharedMutationChecks.writer(access);
            var review = NativeRowReviewRows.locked(connection, account, actor, id);
            NativeSharedMutationChecks.review(access, review);
            if (review.status().equals("SUCCEEDED")) return review;
            require(
                List.of("APPLYING", "UNKNOWN").contains(review.status()),
                "REVIEW_STATE_CONFLICT"
            );
            require(clock.instant().isBefore(review.expiresAt()), "REVIEW_EXPIRED");
            require(scope.equals(review.scopeDigest()), "ACCESS_SCOPE_CHANGED");
            var dataset = NativeRowTransactions.head(
                connection,
                account,
                review.datasetId(),
                review.schemaRevision()
            );
            var document = NativeMutationValidation.PARSER.parse(review.canonicalDocument());
            String canonical = NativeMutationValidation.compile(
                connection,
                dataset,
                scope,
                document,
                review.createdRecordId(),
                null,
                access
            );
            require(
                canonical.equals(review.canonicalDocument()) &&
                    VclCanonical.sha256(canonical).equals(review.documentDigest()),
                "REVIEW_INTEGRITY"
            );
            var result = NativeRowDml.mutate(
                connection,
                dataset,
                document.spec(),
                review.createdRecordId()
            );
            var payload = JsonNodeFactory.instance
                .objectNode()
                .put("mutationId", review.mutationId().toString())
                .put("datasetId", review.datasetId().toString())
                .put("recordId", result.recordId().toString())
                .put("recordVersion", Long.toString(result.recordVersion()))
                .put("dataVersion", result.dataVersion())
                .put("generation", dataset.generation().toString())
                .put("schemaRevision", dataset.schema().revision())
                .put("deleted", result.deleted());
            String bytes = VclCanonical.encode(payload);
            complete(connection, review, document.spec().path("operation").asText(), bytes);
            return review.succeeded(bytes);
        });
    }

    private static void complete(
        Connection connection,
        NativeRowReview review,
        String operation,
        String bytes
    ) throws SQLException {
        String receipt = """
        INSERT INTO cfg_row_receipt(id,account_id,review_id,operation,idempotency_key,payload_digest,result_json)
        VALUES(?,?,?,?,?,?,?)
        """;
        String finish =
            "UPDATE cfg_row_review SET status='SUCCEEDED',outcome_code=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=?";
        if (connection.getMetaData().getDatabaseProductName().equals("PostgreSQL")) {
            try (
                var statement = connection.prepareStatement(
                    "WITH receipt AS (" +
                        receipt +
                        """
                        RETURNING id,account_id,result_json), event AS (
                            INSERT INTO cfg_outbox(id,account_id,aggregate_id,event_type,payload_json)
                            SELECT id,account_id,?,'NATIVE_ROW_MUTATED',result_json FROM receipt RETURNING id
                        )
                        """ +
                        finish +
                        " AND receipt_id IN (SELECT id FROM event)"
                )
            ) {
                statement.setObject(1, review.receiptId());
                statement.setObject(2, review.accountId());
                statement.setObject(3, review.id());
                statement.setString(4, operation);
                statement.setString(5, review.reservationKey());
                statement.setString(6, review.documentDigest());
                statement.setString(7, bytes);
                statement.setObject(8, review.datasetId());
                statement.setObject(9, review.id());
                require(statement.executeUpdate() == 1, "REVIEW_STATE_CONFLICT");
            }
            return;
        }
        JdbcNativeDatasetCatalog.execute(
            connection,
            receipt,
            review.receiptId(),
            review.accountId(),
            review.id(),
            operation,
            review.reservationKey(),
            review.documentDigest(),
            bytes
        );
        JdbcNativeDatasetCatalog.execute(
            connection,
            "INSERT INTO cfg_outbox(id,account_id,aggregate_id,event_type,payload_json) VALUES(?,?,?,'NATIVE_ROW_MUTATED',?)",
            review.receiptId(),
            review.accountId(),
            review.datasetId(),
            bytes
        );
        try (var statement = connection.prepareStatement(finish)) {
            statement.setObject(1, review.id());
            require(statement.executeUpdate() == 1, "REVIEW_STATE_CONFLICT");
        }
    }
}
