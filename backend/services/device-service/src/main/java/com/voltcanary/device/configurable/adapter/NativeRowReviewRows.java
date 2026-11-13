package com.voltcanary.device.configurable.adapter;

import com.voltcanary.device.configurable.domain.NativeRowReview;
import com.voltcanary.vcl.VclException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.UUID;

final class NativeRowReviewRows {
    private NativeRowReviewRows() { }

    static NativeRowReview locked(Connection connection, UUID account, UUID actor, UUID id) throws SQLException {
        try (var query = connection.prepareStatement("SELECT * FROM cfg_row_review WHERE id=? AND account_id=? AND actor_id=? FOR UPDATE")) {
            query.setObject(1, id);
            query.setObject(2, account);
            query.setObject(3, actor);
            try (var row = query.executeQuery()) {
                if (!row.next()) throw new VclException("REVIEW_UNAVAILABLE", "");
                String result = null;
                try (var receipt = connection.prepareStatement("SELECT result_json FROM cfg_row_receipt WHERE review_id=? AND account_id=?")) {
                    receipt.setObject(1, id);
                    receipt.setObject(2, account);
                    try (var rows = receipt.executeQuery()) { if (rows.next()) result = rows.getString(1); }
                }
                return new NativeRowReview(id, account, actor, row.getObject("dataset_id", UUID.class), row.getLong("schema_revision"),
                        row.getObject("mutation_id", UUID.class), row.getObject("created_record_id", UUID.class), row.getObject("receipt_id", UUID.class),
                        row.getString("reservation_key"), row.getString("status"), row.getLong("revision"), row.getObject("expires_at", OffsetDateTime.class).toInstant(),
                        row.getString("canonical_document"), row.getString("document_digest"), row.getString("semantic_digest"), row.getString("review_digest"),
                        row.getString("scope_digest"), row.getString("outcome_code"), result);
            }
        }
    }

    static void require(boolean condition, String code) { if (!condition) throw new VclException(code, ""); }
}
