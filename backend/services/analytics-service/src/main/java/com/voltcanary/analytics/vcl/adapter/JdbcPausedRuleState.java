package com.voltcanary.analytics.vcl.adapter;

import com.voltcanary.analytics.vcl.KeyedStateStore;
import com.voltcanary.analytics.vcl.KeyedStateStore.Acknowledgement;
import com.voltcanary.analytics.vcl.KeyedStateStore.DueTimer;
import com.voltcanary.analytics.vcl.KeyedStateStore.Key;
import com.voltcanary.analytics.vcl.KeyedStateStore.Output;
import com.voltcanary.analytics.vcl.KeyedStateStore.OutputKind;
import com.voltcanary.analytics.vcl.KeyedStateStore.State;
import com.voltcanary.analytics.vcl.KeyedStateStore.TimerKind;
import com.voltcanary.analytics.vcl.KeyedStateStore.Transition;
import com.voltcanary.analytics.vcl.adapter.JdbcKeyedStateStore.PausedWindow;
import com.voltcanary.analytics.vcl.adapter.JdbcKeyedStateStore.PendingSustained;
import com.voltcanary.analytics.vcl.adapter.JdbcKeyedStateStore.Row;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclException;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

final class JdbcPausedRuleState {
    private final JdbcKeyedStateStore ownerContext;

    JdbcPausedRuleState(JdbcKeyedStateStore ownerContext) {
        this.ownerContext = ownerContext;
    }

    static String sustainedCandidatePattern() {
        return "%\"profile\":\"" + com.voltcanary.analytics.vcl.DurableSustained.PROFILE + "\"%";
    }

    java.util.List<PendingSustained> pendingSustained(
            JdbcRuleDeployments.Deployment deployment, int limit, int offset) {
        if (limit < 1 || limit > 128 || offset < 0 || offset > 1_000_000)
            throw new IllegalArgumentException("Invalid measure page");
        try (var connection = ownerContext.connection();
                var query =
                        JdbcKeyedStateStore.prepare(
                                connection,
                                """
                                SELECT s.subject_digest,s.occurrence_digest,s.key_digest,s.pins_digest,o.output_id
                                FROM vcl_keyed_outbox o JOIN vcl_keyed_state s ON s.account_id=o.account_id AND s.key_digest=o.key_digest
                                WHERE s.account_id=? AND s.rule_id=? AND s.deployment_epoch=? AND o.output_kind='MEASURE'
                                    AND o.acknowledged_at IS NULL AND o.payload LIKE ?
                                    AND NOT EXISTS (SELECT 1 FROM vcl_keyed_outbox earlier
                                        WHERE earlier.account_id=o.account_id AND earlier.key_digest=o.key_digest
                                            AND earlier.output_kind='MEASURE' AND earlier.acknowledged_at IS NULL
                                            AND earlier.state_revision<o.state_revision AND earlier.payload LIKE ?)
                                ORDER BY s.key_digest,o.state_revision,o.output_id LIMIT ? OFFSET ?
                                """)) {
            query.setObject(1, deployment.account());
            query.setObject(2, deployment.rule());
            query.setObject(3, deployment.epoch());
            String profile = sustainedCandidatePattern();
            query.setString(4, profile);
            query.setString(5, profile);
            query.setInt(6, limit);
            query.setInt(7, offset);
            try (var rows = query.executeQuery()) {
                var result = new java.util.ArrayList<PendingSustained>();
                while (rows.next()) {
                    var key =
                            new Key(
                                    deployment.account(),
                                    deployment.rule(),
                                    deployment.epoch(),
                                    rows.getString(1),
                                    rows.getString(2));
                    JdbcKeyedStateStore.require(
                            key.digest().equals(rows.getString(3)), "KEY_IDENTITY_CHANGED");
                    result.add(
                            new PendingSustained(
                                    key, rows.getString(4), rows.getObject(5, UUID.class)));
                }
                return java.util.List.copyOf(result);
            }
        } catch (SQLException error) {
            throw JdbcKeyedStateStore.unavailable();
        }
    }

    java.util.List<PendingSustained> pausedSustained(int limit, int offset) {
        if (limit < 1 || limit > 128 || offset < 0 || offset > 1_000_000)
            throw new IllegalArgumentException("Invalid pause page");
        try (var connection = ownerContext.connection();
                var query =
                        JdbcKeyedStateStore.prepare(
                                connection,
                                """
                                SELECT s.account_id,s.rule_id,s.deployment_epoch,s.subject_digest,s.occurrence_digest,
                                    s.key_digest,s.pins_digest,o.output_id
                                FROM vcl_keyed_outbox o JOIN vcl_keyed_state s ON s.account_id=o.account_id AND s.key_digest=o.key_digest
                                JOIN vcl_rule_deployments d ON d.account_id=s.account_id AND d.deployment_epoch=s.deployment_epoch
                                    AND d.rule_id=s.rule_id
                                WHERE d.paused_at IS NOT NULL AND o.output_kind='MEASURE' AND o.acknowledged_at IS NULL AND o.payload LIKE ?
                                ORDER BY s.account_id,s.key_digest,o.state_revision,o.output_id LIMIT ? OFFSET ?
                                """)) {
            query.setString(1, sustainedCandidatePattern());
            query.setInt(2, limit);
            query.setInt(3, offset);
            try (var rows = query.executeQuery()) {
                var result = new java.util.ArrayList<PendingSustained>();
                while (rows.next()) {
                    var key =
                            new Key(
                                    rows.getObject(1, UUID.class),
                                    rows.getObject(2, UUID.class),
                                    rows.getObject(3, UUID.class),
                                    rows.getString(4),
                                    rows.getString(5));
                    JdbcKeyedStateStore.require(
                            key.digest().equals(rows.getString(6)), "KEY_IDENTITY_CHANGED");
                    result.add(
                            new PendingSustained(
                                    key, rows.getString(7), rows.getObject(8, UUID.class)));
                }
                return java.util.List.copyOf(result);
            }
        } catch (SQLException error) {
            throw JdbcKeyedStateStore.unavailable();
        }
    }

    void closePausedSustained(PendingSustained pending, JdbcRuleDeployments.Deployment deployment) {
        var key = pending.key();
        JdbcKeyedStateStore.require(
                deployment.state() == JdbcRuleDeployments.State.PAUSED
                        && key.accountId().equals(deployment.account())
                        && key.ruleId().equals(deployment.rule())
                        && key.deploymentEpoch().equals(deployment.epoch()),
                "DEPLOYMENT_CHANGED");
        ownerContext.transaction(
                connection -> {
                    JdbcRuleDeployments.lockCurrent(connection, deployment);
                    var row = ownerContext.load(connection, key, true);
                    JdbcKeyedStateStore.require(
                            row.pins().equals(pending.pins()), "KEY_PINS_CHANGED");
                    long revision = row.snapshot().revision();
                    var source =
                            JdbcKeyedOutbox.find(connection, key, revision, pending.measure())
                                    .orElseThrow(
                                            () -> new VclException("KEY_OUTPUT_UNAVAILABLE", ""));
                    if (source.receiptId() != null) return null;
                    var candidate =
                            JdbcKeyedStateStore.ALERT_PARSER.readJson(
                                    source.output()
                                            .payload()
                                            .payload()
                                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    JdbcKeyedStateStore.require(
                            source.output().kind() == OutputKind.MEASURE
                                    && com.voltcanary.analytics.vcl.DurableSustained.PROFILE.equals(
                                            candidate.path("profile").textValue()),
                            "SUSTAINED_MEASURE_CHANGED");
                    JdbcKeyedStateStore.require(
                            row.inboxCount() < KeyedStateStore.MAX_INBOX
                                    && JdbcKeyedTimers.read(connection, key, revision).isEmpty(),
                            "SUSTAINED_STATE_INVALID");
                    UUID resultId =
                            SessionAbsenceSchedule.id(
                                    pending.pins(), "sustained-final:" + pending.measure());
                    var measure =
                            com.fasterxml.jackson.databind.node.JsonNodeFactory.instance
                                    .objectNode()
                                    .put("profile", "vcl-sustained-final-measure-1")
                                    .put("sourceMeasure", pending.measure().toString())
                                    .put("occurrence", candidate.path("occurrence").textValue())
                                    .put("status", "UNKNOWN")
                                    .put("reason", "PAUSED")
                                    .putNull("literal");
                    var output =
                            new Output(
                                    resultId,
                                    OutputKind.MEASURE,
                                    null,
                                    State.parse(VclCanonical.encode(measure)));
                    var transition =
                            new Transition(
                                    resultId,
                                    1,
                                    source.output().digest(),
                                    revision,
                                    row.snapshot().state(),
                                    java.util.List.of(),
                                    null,
                                    java.util.List.of(output));
                    long committed = JdbcKeyedStateStore.apply(connection, key, row, transition);
                    JdbcKeyedInbox.insert(connection, key, transition, committed, "OBSERVATION");
                    var receipt =
                            new Acknowledgement(
                                    pending.measure(),
                                    source.output().digest(),
                                    resultId,
                                    output.digest());
                    JdbcKeyedStateStore.require(
                            JdbcKeyedOutbox.acknowledge(
                                            connection,
                                            key,
                                            committed,
                                            java.util.List.of(receipt),
                                            JdbcKeyedStateStore.now(connection),
                                            OutputKind.MEASURE)
                                    == 1,
                            "SUSTAINED_MEASURE_CHANGED");
                    return null;
                });
    }

    java.util.List<PausedWindow> pausedWindows(int limit, int offset) {
        if (limit < 1 || limit > 128 || offset < 0 || offset > 1_000_000)
            throw new IllegalArgumentException("Invalid pause page");
        try (var connection = ownerContext.connection();
                var query =
                        JdbcKeyedStateStore.prepare(
                                connection,
                                """
                                SELECT s.account_id,s.rule_id,s.deployment_epoch,s.subject_digest,s.occurrence_digest,s.key_digest,t.timer_id
                                FROM vcl_keyed_timers t JOIN vcl_keyed_state s ON s.account_id=t.account_id AND s.key_digest=t.key_digest
                                JOIN vcl_rule_deployments d ON d.account_id=s.account_id AND d.deployment_epoch=s.deployment_epoch
                                    AND d.rule_id=s.rule_id
                                WHERE d.paused_at IS NOT NULL AND t.timer_kind IN ('WINDOW_CLOSE','SESSION_TIMEOUT','WAIT_TIMEOUT')
                                ORDER BY s.account_id,s.key_digest,t.timer_id LIMIT ? OFFSET ?
                                """)) {
            query.setInt(1, limit);
            query.setInt(2, offset);
            try (var rows = query.executeQuery()) {
                var result = new java.util.ArrayList<PausedWindow>();
                while (rows.next()) {
                    var key =
                            new Key(
                                    rows.getObject(1, UUID.class),
                                    rows.getObject(2, UUID.class),
                                    rows.getObject(3, UUID.class),
                                    rows.getString(4),
                                    rows.getString(5));
                    JdbcKeyedStateStore.require(
                            key.digest().equals(rows.getString(6)), "KEY_IDENTITY_CHANGED");
                    result.add(new PausedWindow(key, rows.getObject(7, UUID.class)));
                }
                return java.util.List.copyOf(result);
            }
        } catch (SQLException error) {
            throw JdbcKeyedStateStore.unavailable();
        }
    }

    void closePausedWindow(PausedWindow pending, JdbcRuleDeployments.Deployment deployment) {
        var key = pending.key();
        JdbcKeyedStateStore.require(
                deployment.state() == JdbcRuleDeployments.State.PAUSED
                        && key.accountId().equals(deployment.account())
                        && key.ruleId().equals(deployment.rule())
                        && key.deploymentEpoch().equals(deployment.epoch()),
                "DEPLOYMENT_CHANGED");
        ownerContext.transaction(
                connection -> {
                    JdbcRuleDeployments.lockCurrent(connection, deployment);
                    var row = ownerContext.load(connection, key, true);
                    long revision = row.snapshot().revision();
                    var timers = JdbcKeyedTimers.read(connection, key, revision);
                    if (timers.stream()
                            .noneMatch(timer -> pending.timerId().equals(timer.timer().id())))
                        return null;
                    var staged = row.snapshot().state();
                    JdbcKeyedStateStore.require(timers.size() == 1, "WINDOW_TIMER_STATE_CHANGED");
                    if (timers.getFirst().timer().kind() == TimerKind.WAIT_TIMEOUT) {
                        var recipe =
                                SessionAbsenceSchedule.recipe(
                                        new DueTimer(key, row.pins(), timers.getFirst()), staged);
                        var measure =
                                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance
                                        .objectNode()
                                        .put("profile", SessionAbsenceSchedule.MEASURE)
                                        .put("node", recipe.node())
                                        .put("status", "UNKNOWN")
                                        .put("reason", "PAUSED")
                                        .putNull("literal");
                        if (recipe.previous() != null)
                            measure.put("supersedes", recipe.previous().toString());
                        measure.putArray("evidence");
                        writePaused(
                                connection,
                                key,
                                row,
                                staged,
                                staged,
                                State.parse(VclCanonical.encode(measure)));
                        return null;
                    }
                    JdbcKeyedStateStore.require(
                            timers.getFirst().timer().payload().equals(staged),
                            "WINDOW_TIMER_STATE_CHANGED");
                    if (timers.getFirst().timer().kind() == TimerKind.SESSION_TIMEOUT) {
                        var profile =
                                JdbcKeyedStateStore.ALERT_PARSER
                                        .readJson(
                                                staged.payload()
                                                        .getBytes(
                                                                java.nio.charset.StandardCharsets
                                                                        .UTF_8))
                                        .path("profile")
                                        .textValue();
                        var next =
                                com.voltcanary.analytics.vcl.DurableZoneSession.PROFILE.equals(
                                                profile)
                                        ? com.voltcanary.analytics.vcl.DurableZoneSession
                                                .pauseSnapshot(key, staged)
                                        : com.voltcanary.analytics.vcl.DurableWindowSession
                                                .pauseSnapshot(key, staged);
                        writePaused(connection, key, row, staged, next, next);
                        return null;
                    }
                    JdbcKeyedStateStore.require(
                            timers.getFirst().timer().kind() == TimerKind.WINDOW_CLOSE,
                            "WINDOW_TIMER_STATE_CHANGED");
                    if (IntervalWindowSchedule.handles(staged)) {
                        var recipe = IntervalWindowSchedule.recipe(staged);
                        var measure =
                                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance
                                        .objectNode()
                                        .put("profile", "vcl-interval-final-measure-1")
                                        .put("node", recipe.node())
                                        .put("status", "UNKNOWN")
                                        .put("reason", "PAUSED")
                                        .putNull("literal");
                        writePaused(
                                connection,
                                key,
                                row,
                                staged,
                                staged,
                                State.parse(VclCanonical.encode(measure)));
                        return null;
                    }
                    DurableJournalWindow.recipe(staged);
                    var value =
                            (com.fasterxml.jackson.databind.node.ObjectNode)
                                    JdbcKeyedStateStore.ALERT_PARSER.readJson(
                                            staged.payload()
                                                    .getBytes(
                                                            java.nio.charset.StandardCharsets
                                                                    .UTF_8));
                    JdbcKeyedStateStore.require(
                            "STAGED".equals(value.path("phase").textValue()),
                            "WINDOW_TIMER_STATE_CHANGED");
                    value.put("phase", "PAUSED")
                            .put("status", "UNKNOWN")
                            .put("reason", "PAUSED")
                            .putNull("literal")
                            .put("finalized", false)
                            .put("coverage", "UNKNOWN")
                            .put("coverageReason", "PAUSED");
                    value.putArray("evidence");
                    var decision =
                            (com.fasterxml.jackson.databind.node.ObjectNode) value.path("decision");
                    decision.put("status", "UNKNOWN")
                            .put("reason", "PAUSED")
                            .putNull("literal")
                            .putArray("evidence");
                    var next = State.parse(VclCanonical.encode(value));
                    value.remove("recipe");
                    writePaused(
                            connection,
                            key,
                            row,
                            staged,
                            next,
                            State.parse(VclCanonical.encode(value)));
                    return null;
                });
    }

    static void writePaused(
            Connection connection, Key key, Row row, State staged, State next, State measure)
            throws SQLException {
        var output = new Output(pauseId(key, "measure"), OutputKind.MEASURE, null, measure);
        var transition =
                new Transition(
                        pauseId(key, "control"),
                        1,
                        staged.digest(),
                        row.snapshot().revision(),
                        next,
                        java.util.List.of(),
                        null,
                        java.util.List.of(output));
        long committed = JdbcKeyedStateStore.apply(connection, key, row, transition);
        JdbcKeyedInbox.insert(connection, key, transition, committed, "OBSERVATION");
    }

    static UUID pauseId(Key key, String purpose) {
        return UUID.nameUUIDFromBytes(
                ("VCL_PAUSE/1\n" + key.digest() + "\n" + purpose)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
