package com.voltcanary.analytics.vcl.adapter;

import com.voltcanary.analytics.vcl.KeyedStateStore;
import com.voltcanary.analytics.vcl.KeyedStateStore.Acknowledgement;
import com.voltcanary.analytics.vcl.KeyedStateStore.Key;
import com.voltcanary.analytics.vcl.KeyedStateStore.Output;
import com.voltcanary.analytics.vcl.KeyedStateStore.OutputKind;
import com.voltcanary.analytics.vcl.KeyedStateStore.OutputStatus;
import com.voltcanary.analytics.vcl.KeyedStateStore.State;
import com.voltcanary.vcl.VclException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import static com.voltcanary.analytics.vcl.adapter.JdbcKeyedStateStore.prepare;

/** Private durable output metadata; no external transport or authority is supplied by this adapter. */
final class JdbcKeyedOutbox {
    private JdbcKeyedOutbox() { }

    static void append(Connection connection, Key key, long revision, List<Output> outputs) throws SQLException {
        if (outputs.isEmpty()) return;
        var fresh = new ArrayList<Output>();
        for (var output : outputs) {
            target(key, output);
            var existing = find(connection, key, revision - 1, output.id());
            if (existing.isPresent()) require(existing.get().output().equals(output), "KEY_OUTPUT_CONFLICT");
            else fresh.add(output);
        }
        if (fresh.isEmpty()) return;
        int count;
        try (var query = prepare(connection, "SELECT outbox_count FROM vcl_keyed_state WHERE account_id=? AND key_digest=?")) {
            query.setObject(1, key.accountId());
            query.setString(2, key.digest());
            try (var row = query.executeQuery()) {
                require(row.next(), "KEY_UNAVAILABLE");
                count = row.getInt(1);
            }
        }
        require(count <= KeyedStateStore.MAX_OUTPUTS - fresh.size(), "KEY_OUTBOX_LIMIT");
        try (var insert = prepare(connection, """
                INSERT INTO vcl_keyed_outbox(account_id,key_digest,output_id,output_kind,output_digest,state_revision,
                target_account_id,target_rule_id,target_epoch,target_subject_digest,target_occurrence_digest,payload_digest,payload)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
                """)) {
            for (var output : fresh) {
                insert.setObject(1, key.accountId());
                insert.setString(2, key.digest());
                insert.setObject(3, output.id());
                insert.setString(4, output.kind().name());
                insert.setString(5, output.digest());
                insert.setLong(6, revision);
                var target = output.target();
                insert.setObject(7, target == null ? null : target.accountId());
                insert.setObject(8, target == null ? null : target.ruleId());
                insert.setObject(9, target == null ? null : target.deploymentEpoch());
                insert.setString(10, target == null ? null : target.subjectDigest());
                insert.setString(11, target == null ? null : target.occurrenceDigest());
                insert.setString(12, output.payload().digest());
                insert.setString(13, output.payload().payload());
                insert.addBatch();
            }
            insert.executeBatch();
        }
        try (var update = prepare(connection, "UPDATE vcl_keyed_state SET outbox_count=outbox_count+? WHERE account_id=? AND key_digest=?")) {
            update.setInt(1, fresh.size());
            update.setObject(2, key.accountId());
            update.setString(3, key.digest());
            require(update.executeUpdate() == 1, "KEY_UNAVAILABLE");
        }
    }

    static List<OutputStatus> pending(Connection connection, Key key, long revision, int limit) throws SQLException {
        try (var query = prepare(connection, "SELECT * FROM vcl_keyed_outbox WHERE account_id=? AND key_digest=? AND acknowledged_at IS NULL ORDER BY state_revision,output_id FETCH FIRST ? ROWS ONLY")) {
            query.setObject(1, key.accountId());
            query.setString(2, key.digest());
            query.setInt(3, limit);
            try (var rows = query.executeQuery()) {
                var result = new ArrayList<OutputStatus>();
                while (rows.next()) result.add(restore(rows, key, revision));
                return List.copyOf(result);
            }
        }
    }

    static Optional<OutputStatus> find(Connection connection, Key key, long revision, UUID id) throws SQLException {
        try (var query = prepare(connection, "SELECT * FROM vcl_keyed_outbox WHERE account_id=? AND key_digest=? AND output_id=?")) {
            query.setObject(1, key.accountId());
            query.setString(2, key.digest());
            query.setObject(3, id);
            try (var row = query.executeQuery()) { return row.next() ? Optional.of(restore(row, key, revision)) : Optional.empty(); }
        }
    }

    static Optional<OutputStatus> latestMeasure(Connection connection, Key key, long revision) throws SQLException {
        try (var query = prepare(connection, """
                SELECT * FROM vcl_keyed_outbox WHERE account_id=? AND key_digest=? AND output_kind='MEASURE'
                ORDER BY state_revision DESC,output_id FETCH FIRST 1 ROW ONLY
                """)) {
            query.setObject(1, key.accountId());
            query.setString(2, key.digest());
            try (var row = query.executeQuery()) { return row.next() ? Optional.of(restore(row, key, revision)) : Optional.empty(); }
        }
    }

    static int acknowledge(Connection connection, Key key, long revision, List<Acknowledgement> acknowledgements, Instant now,
            OutputKind requiredKind) throws SQLException {
        int updated = 0;
        for (var ack : acknowledgements) {
            var existing = find(connection, key, revision, ack.outputId()).orElseThrow(() -> new VclException("KEY_OUTPUT_UNAVAILABLE", ""));
            var kind = existing.output().kind();
            require(requiredKind == null ? kind != OutputKind.ALERT_PROPOSAL : kind == requiredKind,
                    "KEY_OUTPUT_ACK_AUTHORITY_REQUIRED");
            require(existing.output().digest().equals(ack.outputDigest()), "KEY_OUTPUT_CHANGED");
            if (existing.receiptId() != null) {
                require(existing.receiptId().equals(ack.receiptId()) && existing.receiptDigest().equals(ack.receiptDigest()), "KEY_OUTPUT_ACK_CONFLICT");
                continue;
            }
            try (var update = prepare(connection, "UPDATE vcl_keyed_outbox SET ack_receipt_id=?,ack_receipt_digest=?,acknowledged_at=? WHERE account_id=? AND key_digest=? AND output_id=?")) {
                update.setObject(1, ack.receiptId());
                update.setString(2, ack.receiptDigest());
                update.setObject(3, now.atOffset(ZoneOffset.UTC));
                update.setObject(4, key.accountId());
                update.setString(5, key.digest());
                update.setObject(6, ack.outputId());
                require(update.executeUpdate() == 1, "KEY_OUTPUT_UNAVAILABLE");
                updated++;
            }
        }
        return updated;
    }

    private static OutputStatus restore(ResultSet row, Key key, long maximumRevision) throws SQLException {
        try {
            var kind = OutputKind.valueOf(row.getString("output_kind"));
            Key target = kind == OutputKind.REKEY ? new Key(row.getObject("target_account_id", UUID.class),
                    row.getObject("target_rule_id", UUID.class), row.getObject("target_epoch", UUID.class),
                    row.getString("target_subject_digest"), row.getString("target_occurrence_digest")) : null;
            var output = new Output(row.getObject("output_id", UUID.class), kind, target,
                    State.restore(row.getString("payload"), row.getString("payload_digest")));
            target(key, output);
            require(output.digest().equals(row.getString("output_digest")), "KEY_OUTPUT_CORRUPT");
            long revision = row.getLong("state_revision");
            require(revision > 0 && revision <= maximumRevision, "KEY_OUTPUT_REVISION_CHANGED");
            var receiptId = row.getObject("ack_receipt_id", UUID.class);
            String receiptDigest = row.getString("ack_receipt_digest");
            require((receiptId != null) == (receiptDigest != null)
                    && (receiptId != null) == (row.getObject("acknowledged_at") != null), "KEY_OUTPUT_CORRUPT");
            return new OutputStatus(output, revision, receiptId, receiptDigest);
        } catch (VclException error) {
            throw error;
        } catch (IllegalArgumentException error) {
            throw new VclException("KEY_OUTPUT_CORRUPT", "");
        }
    }

    private static void target(Key source, Output output) {
        if (output.target() != null) require(source.accountId().equals(output.target().accountId())
                && source.ruleId().equals(output.target().ruleId()) && source.deploymentEpoch().equals(output.target().deploymentEpoch()), "KEY_OUTPUT_TARGET_CHANGED");
    }
    private static void require(boolean condition, String code) { if (!condition) throw new VclException(code, ""); }
}
