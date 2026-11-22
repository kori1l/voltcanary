package com.voltcanary.analytics.vcl.adapter;

import static com.voltcanary.analytics.vcl.adapter.JdbcKeyedStateStore.prepare;

import com.voltcanary.analytics.vcl.KeyedStateStore.Key;
import com.voltcanary.analytics.vcl.KeyedStateStore.OutputStatus;
import com.voltcanary.analytics.vcl.KeyedStateStore.Receipt;
import com.voltcanary.analytics.vcl.KeyedStateStore.RekeyReceipt;
import com.voltcanary.analytics.vcl.KeyedStateStore.Transition;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

/** Durable namespaced local transfer receipts; not a remote effect or autonomous grant. */
final class JdbcKeyedRekeys {

    private JdbcKeyedRekeys() {}

    static Optional<RekeyReceipt> find(
        Connection connection,
        Key source,
        Key target,
        OutputStatus output,
        Transition transition,
        long targetRevision,
        String pinsDigest
    ) throws SQLException {
        try (
            var query = prepare(
                connection,
                "SELECT * FROM vcl_keyed_rekey_receipts WHERE account_id=? AND source_key_digest=? AND output_id=?"
            )
        ) {
            query.setObject(1, source.accountId());
            query.setString(2, source.digest());
            query.setObject(3, output.output().id());
            try (var row = query.executeQuery()) {
                if (!row.next()) return Optional.empty();
                require(
                    target.digest().equals(row.getString("target_key_digest")) &&
                        pinsDigest.equals(row.getString("pins_digest")) &&
                        output.stateRevision() == row.getLong("output_revision") &&
                        output.output().digest().equals(row.getString("output_digest")),
                    "KEY_REKEY_RECEIPT_CORRUPT"
                );
                require(
                    transition.digest().equals(row.getString("transition_digest")),
                    "KEY_INPUT_CONFLICT"
                );
                long committed = row.getLong("committed_revision");
                require(
                    committed == transition.expectedRevision() + 1 && committed <= targetRevision,
                    "KEY_REKEY_RECEIPT_CORRUPT"
                );
                UUID id = row.getObject("receipt_id", UUID.class);
                String digest = digest(
                    source,
                    target,
                    output,
                    transition,
                    id,
                    committed,
                    pinsDigest
                );
                require(
                    digest.equals(row.getString("receipt_digest")) &&
                        id.equals(output.receiptId()) &&
                        digest.equals(output.receiptDigest()),
                    "KEY_REKEY_RECEIPT_CORRUPT"
                );
                return Optional.of(new RekeyReceipt(id, digest, new Receipt(committed, true)));
            }
        }
    }

    static RekeyReceipt insert(
        Connection connection,
        Key source,
        Key target,
        OutputStatus output,
        Transition transition,
        String pinsDigest
    ) throws SQLException {
        UUID id = UUID.randomUUID();
        long revision = transition.expectedRevision() + 1;
        String digest = digest(source, target, output, transition, id, revision, pinsDigest);
        try (
            var insert = prepare(
                connection,
                """
                INSERT INTO vcl_keyed_rekey_receipts(account_id,source_key_digest,output_id,target_key_digest,
                output_revision,output_digest,transition_digest,committed_revision,receipt_id,receipt_digest,pins_digest)
                VALUES(?,?,?,?,?,?,?,?,?,?,?)
                """
            )
        ) {
            insert.setObject(1, source.accountId());
            insert.setString(2, source.digest());
            insert.setObject(3, output.output().id());
            insert.setString(4, target.digest());
            insert.setLong(5, output.stateRevision());
            insert.setString(6, output.output().digest());
            insert.setString(7, transition.digest());
            insert.setLong(8, revision);
            insert.setObject(9, id);
            insert.setString(10, digest);
            insert.setString(11, pinsDigest);
            insert.executeUpdate();
        }
        return new RekeyReceipt(id, digest, new Receipt(revision, false));
    }

    private static String digest(
        Key source,
        Key target,
        OutputStatus output,
        Transition transition,
        UUID id,
        long revision,
        String pinsDigest
    ) {
        return VclCanonical.sha256(
            "vcl-keyed-rekey-receipt-1\n" +
                source.digest() +
                "\n" +
                target.digest() +
                "\n" +
                output.output().id() +
                "\n" +
                output.stateRevision() +
                "\n" +
                output.output().digest() +
                "\n" +
                transition.digest() +
                "\n" +
                id +
                "\n" +
                revision +
                "\n" +
                pinsDigest
        );
    }

    private static void require(boolean condition, String code) {
        if (!condition) throw new VclException(code, "");
    }
}
