package com.voltcanary.analytics.vcl.adapter;

import com.voltcanary.analytics.vcl.KeyedStateStore;
import com.voltcanary.analytics.vcl.KeyedStateStore.DueTimer;
import com.voltcanary.analytics.vcl.KeyedStateStore.Key;
import com.voltcanary.analytics.vcl.KeyedStateStore.ScheduledTimer;
import com.voltcanary.analytics.vcl.KeyedStateStore.State;
import com.voltcanary.analytics.vcl.KeyedStateStore.Timer;
import com.voltcanary.analytics.vcl.KeyedStateStore.TimerKind;
import com.voltcanary.analytics.vcl.KeyedStateStore.Transition;
import com.voltcanary.vcl.VclException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static com.voltcanary.analytics.vcl.adapter.JdbcKeyedStateStore.prepare;

/** SQL operations join the caller's already locked key transaction; discovery alone grants no authority. */
final class JdbcKeyedTimers {
    private JdbcKeyedTimers() { }

    record SessionCut(UUID generation, long checkpoint) { }

    static List<ScheduledTimer> read(Connection connection, Key key, long stateRevision) throws SQLException {
        try (var query = prepare(connection, "SELECT * FROM vcl_keyed_timers WHERE account_id=? AND key_digest=? ORDER BY timer_id FETCH FIRST 65 ROWS ONLY")) {
            query.setObject(1, key.accountId());
            query.setString(2, key.digest());
            try (var rows = query.executeQuery()) {
                var result = new ArrayList<ScheduledTimer>();
                int bytes = 0;
                while (rows.next()) {
                    var timer = timer(rows);
                    long revision = rows.getLong("scheduled_revision");
                    require(revision == stateRevision, "KEY_TIMER_REVISION_CHANGED");
                    bytes += timer.payload().payload().getBytes(StandardCharsets.UTF_8).length;
                    require(result.size() < 64 && bytes <= 262144, "KEY_TIMER_LIMIT");
                    result.add(new ScheduledTimer(timer, revision));
                }
                return List.copyOf(result);
            }
        }
    }

    static SessionCut requireDue(Connection connection, Key key, Transition transition, Instant now,
            boolean requireSessionCut) throws SQLException {
        try (var query = prepare(connection, "SELECT * FROM vcl_keyed_timers WHERE account_id=? AND key_digest=? AND timer_id=?")) {
            query.setObject(1, key.accountId());
            query.setString(2, key.digest());
            query.setObject(3, transition.consumedTimer().timerId());
            try (var row = query.executeQuery()) {
                require(row.next(), "KEY_TIMER_CHANGED");
                var timer = timer(row);
                require(row.getLong("scheduled_revision") == transition.consumedTimer().scheduledRevision()
                        && row.getLong("scheduled_revision") == transition.expectedRevision()
                        && !timer.dueAt().isAfter(now), "KEY_TIMER_CHANGED");
                require(timer.digest().equals(transition.inputDigest()), "KEY_TIMER_INPUT_CHANGED");
                if (!requireSessionCut || timer.kind() != TimerKind.SESSION_TIMEOUT) return null;
                var cut = sessionCut(row);
                require(cut != null, "SESSION_TIMEOUT_CUT_UNAVAILABLE");
                return cut;
            }
        }
    }

    static long freezeSessionCut(Connection connection, DueTimer due, UUID generation, long head, Instant now) throws SQLException {
        var expected = due.scheduled();
        require(expected.timer().kind() == TimerKind.SESSION_TIMEOUT, "SESSION_TIMER_CHANGED");
        try (var query = prepare(connection, """
                SELECT * FROM vcl_keyed_timers WHERE account_id=? AND key_digest=? AND timer_id=?
                """)) {
            query.setObject(1, due.key().accountId());
            query.setString(2, due.key().digest());
            query.setObject(3, expected.timer().id());
            try (var row = query.executeQuery()) {
                require(row.next() && row.getLong("scheduled_revision") == expected.stateRevision()
                        && timer(row).equals(expected.timer()) && !expected.timer().dueAt().isAfter(now), "SESSION_TIMER_CHANGED");
                var cut = sessionCut(row);
                if (cut != null) {
                    require(cut.generation().equals(generation) && cut.checkpoint() <= head, "SESSION_TIMEOUT_CUT_CHANGED");
                    return cut.checkpoint();
                }
            }
        }
        try (var update = prepare(connection, """
                UPDATE vcl_keyed_timers SET input_generation=?,input_checkpoint=?
                WHERE account_id=? AND key_digest=? AND timer_id=? AND scheduled_revision=?
                    AND input_generation IS NULL AND input_checkpoint IS NULL
                """)) {
            update.setObject(1, generation);
            update.setLong(2, head);
            update.setObject(3, due.key().accountId());
            update.setString(4, due.key().digest());
            update.setObject(5, expected.timer().id());
            update.setLong(6, expected.stateRevision());
            require(update.executeUpdate() == 1, "SESSION_TIMER_CHANGED");
        }
        return head;
    }

    private static SessionCut sessionCut(ResultSet row) throws SQLException {
        var generation = row.getObject("input_generation", UUID.class);
        if (generation == null) return null;
        long checkpoint = row.getLong("input_checkpoint");
        require(!row.wasNull() && checkpoint >= 0, "SESSION_TIMEOUT_CUT_CHANGED");
        return new SessionCut(generation, checkpoint);
    }

    static void replace(Connection connection, Key key, long revision, List<Timer> timers) throws SQLException {
        try (var delete = prepare(connection, "DELETE FROM vcl_keyed_timers WHERE account_id=? AND key_digest=?")) {
            delete.setObject(1, key.accountId());
            delete.setString(2, key.digest());
            delete.executeUpdate();
        }
        if (timers.isEmpty()) return;
        try (var insert = prepare(connection, "INSERT INTO vcl_keyed_timers(account_id,key_digest,timer_id,scheduled_revision,due_at,timer_kind,payload_digest,payload) VALUES(?,?,?,?,?,?,?,?)")) {
            for (var timer : timers) {
                insert.setObject(1, key.accountId());
                insert.setString(2, key.digest());
                insert.setObject(3, timer.id());
                insert.setLong(4, revision);
                insert.setObject(5, timer.dueAt().atOffset(ZoneOffset.UTC));
                insert.setString(6, timer.kind().name());
                insert.setString(7, timer.payload().digest());
                insert.setString(8, timer.payload().payload());
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    static List<DueTimer> due(Connection connection, UUID accountId, int limit, Instant now) throws SQLException {
        return due(connection, accountId, null, null, null, null, limit, now);
    }

    static List<DueTimer> due(Connection connection, UUID accountId, UUID ruleId, UUID deploymentEpoch,
            TimerKind kind, DueTimer after, int limit, Instant now) throws SQLException {
        String scoped = ruleId == null ? "" : " AND s.rule_id=? AND s.deployment_epoch=?";
        String byKind = kind == null ? "" : " AND t.timer_kind=?";
        String afterCursor = after == null ? "" : " AND (t.due_at,t.key_digest,t.timer_id) > (?,?,?)";
        try (var query = prepare(connection, """
                SELECT t.*,s.rule_id,s.deployment_epoch,s.subject_digest,s.occurrence_digest,s.pins_digest,s.state_version,
                s.state_revision AS current_state_revision
                FROM vcl_keyed_timers t JOIN vcl_keyed_state s ON s.account_id=t.account_id AND s.key_digest=t.key_digest
                WHERE t.account_id=? AND t.due_at<=?
                """ + scoped + byKind + afterCursor + " ORDER BY t.due_at,t.key_digest,t.timer_id FETCH FIRST ? ROWS ONLY")) {
            query.setObject(1, accountId);
            query.setObject(2, now.atOffset(ZoneOffset.UTC));
            int parameter = 3;
            if (ruleId != null) {
                query.setObject(parameter++, ruleId);
                query.setObject(parameter++, deploymentEpoch);
            }
            if (kind != null) {
                query.setString(parameter++, kind.name());
            }
            if (after != null) {
                query.setObject(parameter++, after.scheduled().timer().dueAt().atOffset(ZoneOffset.UTC));
                query.setString(parameter++, after.key().digest());
                query.setObject(parameter++, after.scheduled().timer().id());
            }
            query.setInt(parameter, limit);
            try (var rows = query.executeQuery()) {
                var result = new ArrayList<DueTimer>();
                while (rows.next()) {
                    require(KeyedStateStore.VERSION.equals(rows.getString("state_version")), "KEY_VERSION_UNAVAILABLE");
                    require(rows.getLong("scheduled_revision") == rows.getLong("current_state_revision"), "KEY_TIMER_REVISION_CHANGED");
                    var key = new Key(accountId, rows.getObject("rule_id", UUID.class), rows.getObject("deployment_epoch", UUID.class),
                            rows.getString("subject_digest"), rows.getString("occurrence_digest"));
                    require(key.digest().equals(rows.getString("key_digest")), "KEY_IDENTITY_CHANGED");
                    result.add(new DueTimer(key, rows.getString("pins_digest"), new ScheduledTimer(timer(rows), rows.getLong("scheduled_revision"))));
                }
                return List.copyOf(result);
            }
        }
    }

    private static Timer timer(ResultSet row) throws SQLException {
        try {
            var time = row.getObject("due_at", OffsetDateTime.class);
            require(time != null, "KEY_TIMER_CORRUPT");
            return new Timer(row.getObject("timer_id", UUID.class), TimerKind.valueOf(row.getString("timer_kind")), time.toInstant(),
                    State.restore(row.getString("payload"), row.getString("payload_digest")));
        } catch (VclException error) {
            throw error;
        } catch (IllegalArgumentException error) {
            throw new VclException("KEY_TIMER_CORRUPT", "");
        }
    }
    private static void require(boolean condition, String code) { if (!condition) throw new VclException(code, ""); }
}
