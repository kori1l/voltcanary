package com.voltcanary.analytics.vcl.adapter;

import com.voltcanary.analytics.vcl.KeyedStateStore.InputReceipt;
import com.voltcanary.analytics.vcl.KeyedStateStore.Key;
import com.voltcanary.analytics.vcl.KeyedStateStore.Transition;
import com.voltcanary.analytics.vcl.KeyedStateStore;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import static com.voltcanary.analytics.vcl.adapter.JdbcKeyedStateStore.prepare;

/** Original transition replay plus integrity-checked new input receipts; legacy rows are never backfilled. */
final class JdbcKeyedInbox {
    private JdbcKeyedInbox() { }

    static Optional<InputReceipt> find(Connection connection, Key key, long stateRevision, String kind, UUID inputId, long inputRevision) throws SQLException {
        try (var query = prepare(connection, "SELECT * FROM vcl_keyed_inbox WHERE account_id=? AND key_digest=? AND input_id=? AND input_revision=? AND input_kind=?")) {
            query.setObject(1, key.accountId());
            query.setString(2, key.digest());
            query.setObject(3, inputId);
            query.setLong(4, inputRevision);
            query.setString(5, kind);
            try (var row = query.executeQuery()) {
                if (!row.next()) return Optional.empty();
                String input = row.getString("input_digest");
                String transition = row.getString("transition_digest");
                String receipt = row.getString("receipt_digest");
                String outputs = row.getString("outputs_digest");
                long committed = row.getLong("committed_revision");
                require(transition != null && transition.matches("[0-9a-f]{64}") && committed > 0 && committed <= stateRevision);
                require((input == null) == (receipt == null));
                require((input == null) == (outputs == null));
                if (input != null) {
                    require(input.matches("[0-9a-f]{64}") && outputs.matches("[0-9a-f]{64}")
                            && digest(key, kind, inputId, inputRevision, input, outputs, transition, committed).equals(receipt));
                }
                return Optional.of(new InputReceipt(input, outputs, transition, committed));
            }
        }
    }

    static void insert(Connection connection, Key key, Transition transition, long revision, String kind) throws SQLException {
        try (var insert = prepare(connection, """
                INSERT INTO vcl_keyed_inbox(account_id,key_digest,input_id,input_revision,transition_digest,
                committed_revision,input_kind,input_digest,receipt_digest,outputs_digest) VALUES(?,?,?,?,?,?,?,?,?,?)
                """)) {
            insert.setObject(1, key.accountId());
            insert.setString(2, key.digest());
            insert.setObject(3, transition.inputId());
            insert.setLong(4, transition.inputRevision());
            insert.setString(5, transition.digest());
            insert.setLong(6, revision);
            insert.setString(7, kind);
            insert.setString(8, transition.inputDigest());
            String outputs = KeyedStateStore.outputsDigest(transition.outputs());
            insert.setString(9, digest(key, kind, transition.inputId(), transition.inputRevision(), transition.inputDigest(), outputs, transition.digest(), revision));
            insert.setString(10, outputs);
            insert.executeUpdate();
        }
    }

    private static String digest(Key key, String kind, UUID id, long inputRevision, String input, String outputs, String transition, long committed) {
        return VclCanonical.sha256("vcl-keyed-input-receipt-1\n" + key.digest() + "\n" + kind + "\n" + id + "\n"
                + inputRevision + "\n" + input + "\n" + outputs + "\n" + transition + "\n" + committed);
    }
    private static void require(boolean condition) { if (!condition) throw new VclException("KEY_RECEIPT_CORRUPT", ""); }
}
