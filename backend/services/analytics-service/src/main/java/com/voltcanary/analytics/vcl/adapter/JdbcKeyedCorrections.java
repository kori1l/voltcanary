package com.voltcanary.analytics.vcl.adapter;

import com.voltcanary.analytics.vcl.KeyedStateStore;
import com.voltcanary.analytics.vcl.KeyedStateStore.Key;
import com.voltcanary.analytics.vcl.KeyedStateStore.Output;
import com.voltcanary.analytics.vcl.KeyedStateStore.OutputKind;
import com.voltcanary.analytics.vcl.KeyedStateStore.State;
import com.voltcanary.analytics.vcl.KeyedStateStore.Timer;
import com.voltcanary.analytics.vcl.KeyedStateStore.Transition;
import com.voltcanary.analytics.vcl.adapter.JdbcKeyedStateStore.CorrectedWindow;
import com.voltcanary.analytics.vcl.adapter.JdbcKeyedStateStore.HistoricalMeasureCandidate;
import com.voltcanary.analytics.vcl.adapter.JdbcKeyedStateStore.HistoricalWindow;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

final class JdbcKeyedCorrections {

    private final JdbcKeyedStateStore ownerContext;

    JdbcKeyedCorrections(JdbcKeyedStateStore ownerContext) {
        this.ownerContext = ownerContext;
    }

    List<HistoricalMeasureCandidate> finalizedIntervalMeasures(
        JdbcRuleDeployments.Deployment deployment,
        UUID after
    ) {
        try (
            var connection = ownerContext.connection();
            var query = JdbcKeyedStateStore.prepare(
                connection,
                """
                SELECT o.output_id,o.payload,o.payload_digest
                FROM vcl_keyed_state s JOIN vcl_keyed_outbox o ON o.account_id=s.account_id AND o.key_digest=s.key_digest
                WHERE s.account_id=? AND s.rule_id=? AND s.deployment_epoch=? AND o.output_kind='MEASURE'
                    AND o.payload LIKE '%"profile":"vcl-interval-final-measure-1"%'
                    AND o.payload LIKE '%"finalized":true%'
                """ +
                    (after == null ? "" : " AND o.output_id>?") +
                    " ORDER BY o.output_id FETCH FIRST 26 ROWS ONLY"
            )
        ) {
            query.setObject(1, deployment.account());
            query.setObject(2, deployment.rule());
            query.setObject(3, deployment.epoch());
            if (after != null) query.setObject(4, after);
            try (var rows = query.executeQuery()) {
                var results = new java.util.ArrayList<HistoricalMeasureCandidate>();
                while (rows.next()) {
                    results.add(
                        new HistoricalMeasureCandidate(
                            rows.getObject(1, UUID.class),
                            State.restore(rows.getString(2), rows.getString(3))
                        )
                    );
                }
                return List.copyOf(results);
            }
        } catch (SQLException failure) {
            throw JdbcKeyedStateStore.unavailable();
        }
    }

    UUID appendWindowCorrection(
        JdbcRuleDeployments.Deployment deployment,
        HistoricalWindow historical,
        UUID correction,
        String confirmationDigest,
        String payload,
        java.util.List<Output> outputs,
        RuleCompilationSnapshot.CorrectionCatchup catchup
    ) {
        var key = historical.key();
        JdbcKeyedStateStore.require(
            deployment.account().equals(key.accountId()) &&
                deployment.rule().equals(key.ruleId()) &&
                deployment.epoch().equals(key.deploymentEpoch()),
            "WORKER_TARGET_INVALID"
        );
        JdbcKeyedStateStore.require(
            confirmationDigest.equals(VclCanonical.sha256(payload)) &&
                payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <=
                    KeyedStateStore.MAX_STATE_BYTES,
            "WINDOW_CORRECTION_LIMIT"
        );
        var lease = outputs.isEmpty()
            ? null
            : ownerContext.acquire(key, historical.pins(), correction);
        return ownerContext.transaction(connection -> {
            JdbcRuleDeployments.lockExecutable(connection, deployment, outputs.isEmpty());
            var row = ownerContext.load(connection, key, true);
            var original = JdbcKeyedOutbox.find(
                connection,
                key,
                row.snapshot().revision(),
                historical.output().id()
            ).orElseThrow(() -> new VclException("WINDOW_MEASURE_UNAVAILABLE", ""));
            JdbcKeyedStateStore.require(
                historical.output().equals(original.output()),
                "WINDOW_MEASURE_CHANGED"
            );
            boolean recorded = false;
            UUID proposal = null;
            try (
                var query = JdbcKeyedStateStore.prepare(
                    connection,
                    """
                    SELECT key_digest,original_measure_id,confirmation_digest,canonical_payload,alert_proposal_id
                    FROM vcl_reviewed_window_corrections WHERE account_id=? AND deployment_epoch=? AND correction_id=?
                    """
                )
            ) {
                query.setObject(1, key.accountId());
                query.setObject(2, key.deploymentEpoch());
                query.setObject(3, correction);
                try (var rows = query.executeQuery()) {
                    if (rows.next()) {
                        JdbcKeyedStateStore.require(
                            key.digest().equals(rows.getString(1)) &&
                                original.output().id().equals(rows.getObject(2, UUID.class)) &&
                                confirmationDigest.equals(rows.getString(3)) &&
                                payload.equals(rows.getString(4)),
                            "WINDOW_CORRECTION_CHANGED"
                        );
                        recorded = true;
                        proposal = rows.getObject(5, UUID.class);
                    }
                }
            }
            if (!recorded) try (
                var insert = JdbcKeyedStateStore.prepare(
                    connection,
                    """
                    INSERT INTO vcl_reviewed_window_corrections(account_id,deployment_epoch,correction_id,key_digest,
                        original_measure_id,confirmation_digest,canonical_payload,window_start,window_end) VALUES(?,?,?,?,?,?,?,?,?)
                    """
                )
            ) {
                insert.setObject(1, key.accountId());
                insert.setObject(2, key.deploymentEpoch());
                insert.setObject(3, correction);
                insert.setString(4, key.digest());
                insert.setObject(5, original.output().id());
                insert.setString(6, confirmationDigest);
                insert.setString(7, payload);
                var window = IntervalWindowSchedule.recipe(historical.schedule()).window();
                insert.setObject(8, window.startInclusive().atOffset(ZoneOffset.UTC));
                insert.setObject(9, window.endExclusive().atOffset(ZoneOffset.UTC));
                JdbcKeyedStateStore.require(
                    insert.executeUpdate() == 1,
                    "WINDOW_CORRECTION_CHANGED"
                );
            }
            if (proposal != null || outputs.isEmpty()) return proposal;
            JdbcKeyedStateStore.require(
                outputs.size() == 2 &&
                    outputs.getFirst().kind() == OutputKind.MEASURE &&
                    outputs.getLast().kind() == OutputKind.ALERT_PROPOSAL,
                "WINDOW_CORRECTION_CHANGED"
            );
            JdbcKeyedStateStore.requireLease(row, lease, JdbcKeyedStateStore.now(connection));
            long revision = row.snapshot().revision();
            var latest = JdbcKeyedOutbox.latestMeasure(connection, key, revision).orElseThrow(() ->
                new VclException("WINDOW_MEASURE_UNAVAILABLE", "")
            );
            JdbcKeyedStateStore.require(
                latest.output().equals(original.output()) &&
                    JdbcKeyedTimers.read(connection, key, revision).isEmpty(),
                "WINDOW_MEASURE_CHANGED"
            );
            requireCorrectedWindowBudget(connection, key);
            var schedule = IntervalWindowSchedule.recipe(row.snapshot().state());
            JdbcKeyedStateStore.require(
                catchup != null &&
                    schedule.generation().equals(catchup.generation()) &&
                    JdbcKeyedStateStore.openingCheckpoint(
                        connection,
                        deployment,
                        catchup.generation(),
                        0
                    ) <= catchup.scannedHead(),
                "WINDOW_CORRECTION_SOURCE_ADVANCED"
            );
            var checkpoint = JdbcKeyedStateStore.ALERT_PARSER.readJson(
                original
                    .output()
                    .payload()
                    .payload()
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)
            )
                .path("checkpoint")
                .textValue();
            boolean hasCatchup =
                catchup.lastRelevant() >
                com.voltcanary.vcl.VclObservationReadiness.checkpoint(checkpoint);
            var next = hasCatchup
                ? schedule.advance(
                      Math.max(schedule.minimumCheckpoint(), catchup.lastRelevant()),
                      outputs.getFirst().id()
                  )
                : schedule;
            var timers = hasCatchup
                ? java.util.List.of(IntervalWindowSchedule.timer(row.pins(), next))
                : java.util.List.<Timer>of();
            var transition = new Transition(
                correction,
                1,
                confirmationDigest,
                revision,
                hasCatchup ? next.state() : row.snapshot().state(),
                timers,
                null,
                outputs
            );
            long committed = JdbcKeyedStateStore.apply(connection, key, row, transition);
            JdbcKeyedInbox.insert(connection, key, transition, committed, "OBSERVATION");
            proposal = outputs.getLast().id();
            try (
                var update = JdbcKeyedStateStore.prepare(
                    connection,
                    """
                    UPDATE vcl_reviewed_window_corrections SET alert_proposal_id=?
                    WHERE account_id=? AND deployment_epoch=? AND correction_id=? AND alert_proposal_id IS NULL
                    """
                )
            ) {
                update.setObject(1, proposal);
                update.setObject(2, key.accountId());
                update.setObject(3, key.deploymentEpoch());
                update.setObject(4, correction);
                JdbcKeyedStateStore.require(
                    update.executeUpdate() == 1,
                    "WINDOW_CORRECTION_CHANGED"
                );
            }
            try (
                var update = JdbcKeyedStateStore.prepare(
                    connection,
                    """
                    UPDATE vcl_rule_deployments SET reference_correction_revision=reference_correction_revision+1
                    WHERE account_id=? AND deployment_epoch=? AND reference_correction_revision<?
                    """
                )
            ) {
                update.setObject(1, key.accountId());
                update.setObject(2, key.deploymentEpoch());
                update.setLong(3, Long.MAX_VALUE);
                JdbcKeyedStateStore.require(update.executeUpdate() == 1, "WINDOW_CORRECTION_LIMIT");
            }
            JdbcKeyedStateStore.requireLease(row, lease, JdbcKeyedStateStore.now(connection));
            return proposal;
        });
    }

    static void requireCorrectedWindowBudget(Connection connection, Key key) throws SQLException {
        try (
            var query = JdbcKeyedStateStore.prepare(
                connection,
                """
                SELECT COUNT(DISTINCT key_digest) FROM vcl_reviewed_window_corrections
                WHERE account_id=? AND deployment_epoch=? AND alert_proposal_id IS NOT NULL AND key_digest<>?
                """
            )
        ) {
            query.setObject(1, key.accountId());
            query.setObject(2, key.deploymentEpoch());
            query.setString(3, key.digest());
            try (var rows = query.executeQuery()) {
                JdbcKeyedStateStore.require(
                    rows.next() && rows.getLong(1) < JdbcKeyedStateStore.MAX_CORRECTED_WINDOWS,
                    "WINDOW_CORRECTION_LIMIT"
                );
            }
        }
    }

    java.util.List<CorrectedWindow> correctedWindows(
        JdbcRuleDeployments.Deployment deployment,
        Instant from,
        Instant until
    ) {
        try (
            var connection = ownerContext.connection();
            var query = JdbcKeyedStateStore.prepare(
                connection,
                """
                SELECT DISTINCT s.subject_digest,s.occurrence_digest,s.key_digest,s.pins_digest
                FROM vcl_reviewed_window_corrections c
                JOIN vcl_keyed_state s ON s.account_id=c.account_id AND s.key_digest=c.key_digest
                WHERE c.account_id=? AND c.deployment_epoch=? AND s.rule_id=? AND s.deployment_epoch=c.deployment_epoch
                    AND c.alert_proposal_id IS NOT NULL AND c.window_start<? AND c.window_end>?
                ORDER BY s.key_digest LIMIT ?
                """
            )
        ) {
            query.setObject(1, deployment.account());
            query.setObject(2, deployment.epoch());
            query.setObject(3, deployment.rule());
            query.setObject(4, until.atOffset(ZoneOffset.UTC));
            query.setObject(5, from.atOffset(ZoneOffset.UTC));
            query.setInt(6, JdbcKeyedStateStore.MAX_CORRECTED_WINDOWS + 1);
            try (var rows = query.executeQuery()) {
                var result = new java.util.ArrayList<CorrectedWindow>();
                while (rows.next()) {
                    var key = new Key(
                        deployment.account(),
                        deployment.rule(),
                        deployment.epoch(),
                        rows.getString(1),
                        rows.getString(2)
                    );
                    JdbcKeyedStateStore.require(
                        key.digest().equals(rows.getString(3)) &&
                            result.size() < JdbcKeyedStateStore.MAX_CORRECTED_WINDOWS,
                        "WINDOW_CORRECTION_LIMIT"
                    );
                    result.add(new CorrectedWindow(key, rows.getString(4)));
                }
                return java.util.List.copyOf(result);
            }
        } catch (SQLException error) {
            throw JdbcKeyedStateStore.unavailable();
        }
    }

    java.util.List<CorrectedWindow> registeredWindows(
        JdbcRuleDeployments.Deployment deployment,
        java.util.List<String> occurrences
    ) {
        if (occurrences.isEmpty()) return java.util.List.of();
        JdbcKeyedStateStore.require(occurrences.size() <= 64, "WINDOW_CORRECTION_LIMIT");
        var parameters = String.join(",", java.util.Collections.nCopies(occurrences.size(), "?"));
        try (
            var connection = ownerContext.connection();
            var query = JdbcKeyedStateStore.prepare(
                connection,
                """
                SELECT subject_digest,occurrence_digest,key_digest,pins_digest
                FROM vcl_keyed_state
                WHERE account_id=? AND rule_id=? AND deployment_epoch=? AND state_revision>0
                  AND occurrence_digest IN (%s)
                ORDER BY occurrence_digest,key_digest LIMIT ?
                """.formatted(parameters)
            )
        ) {
            query.setObject(1, deployment.account());
            query.setObject(2, deployment.rule());
            query.setObject(3, deployment.epoch());
            int parameter = 4;
            for (String occurrence : occurrences) query.setString(parameter++, occurrence);
            query.setInt(parameter, JdbcKeyedStateStore.MAX_CORRECTED_WINDOWS + 1);
            try (var rows = query.executeQuery()) {
                var result = new java.util.ArrayList<CorrectedWindow>();
                while (rows.next()) {
                    var key = new Key(
                        deployment.account(),
                        deployment.rule(),
                        deployment.epoch(),
                        rows.getString("subject_digest"),
                        rows.getString("occurrence_digest")
                    );
                    JdbcKeyedStateStore.require(
                        result.size() < JdbcKeyedStateStore.MAX_CORRECTED_WINDOWS &&
                            key.digest().equals(rows.getString("key_digest")),
                        "WINDOW_CORRECTION_LIMIT"
                    );
                    result.add(new CorrectedWindow(key, rows.getString("pins_digest")));
                }
                return java.util.List.copyOf(result);
            }
        } catch (SQLException error) {
            throw JdbcKeyedStateStore.unavailable();
        }
    }

    HistoricalWindow historicalWindow(JdbcRuleDeployments.Deployment deployment, UUID measure) {
        try (
            var connection = ownerContext.connection();
            var query = JdbcKeyedStateStore.prepare(
                connection,
                """
                SELECT s.subject_digest,s.occurrence_digest,s.key_digest
                FROM vcl_keyed_state s JOIN vcl_keyed_outbox o ON o.account_id=s.account_id AND o.key_digest=s.key_digest
                WHERE s.account_id=? AND s.rule_id=? AND s.deployment_epoch=?
                    AND o.output_id=? AND o.output_kind='MEASURE'
                FETCH FIRST 2 ROWS ONLY
                """
            )
        ) {
            query.setObject(1, deployment.account());
            query.setObject(2, deployment.rule());
            query.setObject(3, deployment.epoch());
            query.setObject(4, measure);
            Key key;
            try (var rows = query.executeQuery()) {
                JdbcKeyedStateStore.require(rows.next(), "WINDOW_MEASURE_UNAVAILABLE");
                key = new Key(
                    deployment.account(),
                    deployment.rule(),
                    deployment.epoch(),
                    rows.getString(1),
                    rows.getString(2)
                );
                JdbcKeyedStateStore.require(
                    key.digest().equals(rows.getString(3)) && !rows.next(),
                    "WINDOW_MEASURE_CHANGED"
                );
            }
            var row = ownerContext.load(connection, key, false);
            var output = JdbcKeyedOutbox.find(
                connection,
                key,
                row.snapshot().revision(),
                measure
            ).orElseThrow(() -> new VclException("WINDOW_MEASURE_UNAVAILABLE", ""));
            JdbcKeyedStateStore.require(
                output.output().kind() == OutputKind.MEASURE,
                "WINDOW_MEASURE_CHANGED"
            );
            return new HistoricalWindow(key, row.pins(), row.snapshot().state(), output.output());
        } catch (SQLException error) {
            throw JdbcKeyedStateStore.unavailable();
        }
    }
}
