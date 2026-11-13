package com.voltcanary.analytics.vcl.adapter;

import com.voltcanary.analytics.vcl.KeyedStateStore;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import javax.sql.DataSource;

/** Isolated owner transactions. No Spring bean, activation, external effects or authority is provided. */
public final class JdbcKeyedStateStore implements KeyedStateStore {
    private static final int MAX_CORRECTED_WINDOWS = 128;
    private static final com.voltcanary.vcl.VclParser ALERT_PARSER = new com.voltcanary.vcl.VclParser();
    private final DataSource source;
    public JdbcKeyedStateStore(DataSource source) { this.source = source; }

    @Override public Snapshot register(Key key, String pinsDigest, State initial) {
        // Validate the immutable pin using the same public value contract as worker leases.
        if (pinsDigest == null || !pinsDigest.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid execution digest");
        String registration = VclCanonical.sha256(VERSION + "\n" + key.digest() + "\n" + pinsDigest + "\n" + initial.digest());
        try (var connection = connection()) {
            try (var insert = prepare(connection, """
                    INSERT INTO vcl_keyed_state(account_id,key_digest,state_version,registration_digest,pins_digest,
                    state_revision,state_digest,state_payload,inbox_count,lease_fence,rule_id,deployment_epoch,subject_digest,occurrence_digest)
                    VALUES(?,?,?,?,?,0,?,?,0,0,?,?,?,?)
                    """)) {
                bindKey(insert, key);
                insert.setString(3, VERSION);
                insert.setString(4, registration);
                insert.setString(5, pinsDigest);
                insert.setString(6, initial.digest());
                insert.setString(7, initial.payload());
                insert.setObject(8, key.ruleId());
                insert.setObject(9, key.deploymentEpoch());
                insert.setString(10, key.subjectDigest());
                insert.setString(11, key.occurrenceDigest());
                insert.executeUpdate();
                return new Snapshot(0, initial);
            }
        } catch (SQLException error) {
            if (!"23505".equals(error.getSQLState())) throw unavailable();
        }
        try (var connection = connection()) {
            var row = load(connection, key, false);
            require(registration.equals(row.registration()), "KEY_REGISTRATION_CHANGED");
            return row.snapshot();
        } catch (SQLException error) { throw unavailable(); }
    }

    @Override public Snapshot inspect(Key key, String pinsDigest) {
        try (var connection = connection()) {
            var row = load(connection, key, false);
            require(row.pins().equals(pinsDigest), "KEY_PINS_CHANGED");
            return row.snapshot();
        } catch (SQLException error) { throw unavailable(); }
    }

    @Override public Lease acquire(Key key, String pinsDigest, UUID holderId) {
        java.util.Objects.requireNonNull(holderId);
        return transaction(connection -> {
            var row = load(connection, key, true);
            require(row.pins().equals(pinsDigest), "KEY_PINS_CHANGED");
            Instant now = now(connection);
            require(row.expiry() == null || !now.isBefore(row.expiry()) || holderId.equals(row.holder()), "KEY_LEASE_BUSY");
            require(row.fence() < Long.MAX_VALUE, "KEY_FENCE_LIMIT");
            long fence = row.fence() + 1;
            Instant expiry = now.plusSeconds(30);
            try (var update = prepare(connection, "UPDATE vcl_keyed_state SET lease_holder=?,lease_fence=?,lease_expires_at=? WHERE account_id=? AND key_digest=?")) {
                update.setObject(1, holderId);
                update.setLong(2, fence);
                update.setObject(3, expiry.atOffset(ZoneOffset.UTC));
                update.setObject(4, key.accountId());
                update.setString(5, key.digest());
                require(update.executeUpdate() == 1, "KEY_UNAVAILABLE");
            }
            return new Lease(holderId, fence, key.digest(), pinsDigest, expiry, row.snapshot(),
                    JdbcKeyedTimers.read(connection, key, row.snapshot().revision()));
        });
    }

    @Override public Receipt commit(Key key, Lease lease, Transition transition) {
        return commit(key, lease, transition, null, null, null, false, false, null);
    }

    Receipt commitJournalWindow(Key key, Lease lease, Transition transition, Instant openedAt,
            JdbcRuleDeployments.Deployment deployment, JdbcJournalWatermarks.Candidate watermark) {
        return commit(key, lease, transition, deployment, java.util.Objects.requireNonNull(openedAt), watermark, false, false, null);
    }

    Receipt commitSession(Key key, Lease lease, Transition transition, Instant openedAt,
            JdbcRuleDeployments.Deployment deployment) {
        return commit(key, lease, transition, java.util.Objects.requireNonNull(deployment),
                java.util.Objects.requireNonNull(openedAt), null, true, true, null);
    }

    Receipt commitReviewedInput(Key key, Lease lease, Transition transition, Instant openedAt,
            JdbcRuleDeployments.Deployment deployment) {
        return commit(key, lease, transition, java.util.Objects.requireNonNull(deployment),
                java.util.Objects.requireNonNull(openedAt), null, true, false, null);
    }

    Receipt commitSustainedFinalization(Key key, Lease lease, Transition transition, Instant openedAt,
            JdbcRuleDeployments.Deployment deployment, Acknowledgement measure) {
        return commit(key, lease, transition, java.util.Objects.requireNonNull(deployment),
                java.util.Objects.requireNonNull(openedAt), null, true, false, java.util.Objects.requireNonNull(measure));
    }

    Receipt commitCoveredAggregate(Key key, Lease lease, Transition transition, Instant openedAt,
            JdbcRuleDeployments.Deployment deployment, JdbcJournalWatermarks.Candidate watermark) {
        return commit(key, lease, transition, java.util.Objects.requireNonNull(deployment), openedAt,
                java.util.Objects.requireNonNull(watermark), true, false, null);
    }

    private Receipt commit(Key key, Lease lease, Transition transition, JdbcRuleDeployments.Deployment deployment,
            Instant openedAt, JdbcJournalWatermarks.Candidate watermark, boolean session, boolean sessionOccurrence,
            Acknowledgement measure) {
        require(key.digest().equals(lease.keyDigest()), "KEY_LEASE_CHANGED");
        if (deployment != null) {
            require(key.accountId().equals(deployment.account()) && key.ruleId().equals(deployment.rule())
                    && key.deploymentEpoch().equals(deployment.epoch()), "WORKER_TARGET_INVALID");
        }
        return transaction(connection -> {
            if (deployment != null) JdbcRuleDeployments.lockExecutable(connection, deployment,
                    transition.consumedTimer() != null || measure != null || sessionOccurrence && transition.expectedRevision() > 0);
            if (session) {
                boolean opening = transition.expectedRevision() == 0;
                JdbcRuleDeployments.fenceSession(connection, deployment, openedAt, opening);
                if (opening && sessionOccurrence) {
                    require(!drainingOccurrence(connection, deployment, key), "SESSION_OCCURRENCE_DRAINING");
                }
            }
            boolean deployed = !session && openedAt != null && JdbcRuleDeployments.fenceJournalWindow(connection, key.accountId(), key.ruleId(),
                    key.deploymentEpoch(), openedAt, transition.consumedTimer() != null,
                    watermark != null && watermark.finalizing() != null);
            var row = load(connection, key, true);
            require(row.pins().equals(lease.pinsDigest()), "KEY_PINS_CHANGED");
            String inputKind = transition.consumedTimer() == null ? "OBSERVATION" : "TIMER";
            var existing = JdbcKeyedInbox.find(connection, key, row.snapshot().revision(), inputKind, transition.inputId(), transition.inputRevision());
            if (existing.isPresent()) {
                var receipt = existing.get();
                require(receipt.transitionDigest().equals(transition.digest()), "KEY_INPUT_CONFLICT");
                require(receipt.committedRevision() == transition.expectedRevision() + 1, "KEY_RECEIPT_CORRUPT");
                return new Receipt(receipt.committedRevision(), true);
            }
            Instant now = now(connection);
            requireLease(row, lease, now);
            require(row.snapshot().revision() == transition.expectedRevision(), "KEY_STATE_CONFLICT");
            require(row.inboxCount() < MAX_INBOX, "KEY_INBOX_LIMIT");
            if (transition.consumedTimer() != null) {
                var cut = JdbcKeyedTimers.requireDue(connection, key, transition, now, sessionOccurrence);
                if (cut != null) {
                    require(openingCheckpoint(connection, deployment, cut.generation(), 0) >= cut.checkpoint(),
                            "SESSION_INPUT_PENDING");
                }
            }
            long revision = apply(connection, key, row, transition);
            if (openedAt != null && transition.consumedTimer() == null && (!session || transition.expectedRevision() == 0)) {
                recordJournalOpening(connection, key, openedAt);
            }
            JdbcKeyedInbox.insert(connection, key, transition, revision, inputKind);
            if (measure != null) {
                require(JdbcKeyedOutbox.acknowledge(connection, key, revision, java.util.List.of(measure), now, OutputKind.MEASURE) == 1,
                        "SUSTAINED_MEASURE_CHANGED");
            }
            if ((deployed || session) && watermark != null) {
                require(transition.consumedTimer() != null, "WINDOW_WATERMARK_INVALID");
                JdbcJournalWatermarks.advance(connection, key, watermark);
                JdbcJournalWatermarks.requireFinalizationFrontier(connection, key, watermark);
            }
            requireLease(row, lease, now(connection));
            return new Receipt(revision, false);
        });
    }

    @Override public java.util.List<DueTimer> dueTimers(UUID accountId, int limit) {
        java.util.Objects.requireNonNull(accountId);
        if (limit < 1 || limit > 128) throw new IllegalArgumentException("Invalid timer discovery limit");
        try (var connection = connection()) {
            return JdbcKeyedTimers.due(connection, accountId, limit, now(connection));
        } catch (SQLException error) { throw unavailable(); }
    }

    @Override public java.util.List<DueTimer> dueTimers(UUID accountId, UUID ruleId, UUID deploymentEpoch, int limit) {
        return dueTimers(accountId, ruleId, deploymentEpoch, null, null, limit);
    }

    @Override public java.util.List<DueTimer> dueTimers(UUID accountId, UUID ruleId, UUID deploymentEpoch,
            TimerKind kind, DueTimer after, int limit) {
        java.util.Objects.requireNonNull(accountId);
        java.util.Objects.requireNonNull(ruleId);
        java.util.Objects.requireNonNull(deploymentEpoch);
        if (limit < 1 || limit > 128) throw new IllegalArgumentException("Invalid timer discovery limit");
        if (after != null && (!accountId.equals(after.key().accountId()) || !ruleId.equals(after.key().ruleId())
                || !deploymentEpoch.equals(after.key().deploymentEpoch()) || kind != after.scheduled().timer().kind())) {
            throw new IllegalArgumentException("Invalid timer discovery cursor");
        }
        try (var connection = connection()) {
            return JdbcKeyedTimers.due(connection, accountId, ruleId, deploymentEpoch, kind, after, limit, now(connection));
        } catch (SQLException error) { throw unavailable(); }
    }

    @Override public java.util.Optional<InputReceipt> inputReceipt(Key key, String pinsDigest, InputKind kind, UUID inputId, long inputRevision) {
        java.util.Objects.requireNonNull(kind);
        java.util.Objects.requireNonNull(inputId);
        if (inputRevision < 1) throw new IllegalArgumentException("Invalid input revision");
        return transaction(connection -> {
            var row = load(connection, key, true);
            require(row.pins().equals(pinsDigest), "KEY_PINS_CHANGED");
            return JdbcKeyedInbox.find(connection, key, row.snapshot().revision(), kind.name(), inputId, inputRevision);
        });
    }

    @Override public java.util.List<OutputStatus> pendingOutputs(Key key, String pinsDigest, int limit) {
        if (limit < 1 || limit > 128) throw new IllegalArgumentException("Invalid outbox read limit");
        return transaction(connection -> {
            var row = load(connection, key, true);
            require(row.pins().equals(pinsDigest), "KEY_PINS_CHANGED");
            return JdbcKeyedOutbox.pending(connection, key, row.snapshot().revision(), limit);
        });
    }

    @Override public OutputStatus inspectOutput(Key key, String pinsDigest, UUID outputId) {
        java.util.Objects.requireNonNull(outputId);
        return transaction(connection -> {
            var row = load(connection, key, true);
            require(row.pins().equals(pinsDigest), "KEY_PINS_CHANGED");
            return JdbcKeyedOutbox.find(connection, key, row.snapshot().revision(), outputId)
                    .orElseThrow(() -> new VclException("KEY_OUTPUT_UNAVAILABLE", ""));
        });
    }

    java.util.Optional<OutputStatus> latestSessionMeasure(Key key, Lease lease) {
        return transaction(connection -> {
            var row = load(connection, key, true);
            requireLease(row, lease, now(connection));
            require(row.snapshot().equals(lease.snapshot()), "KEY_STATE_CONFLICT");
            return JdbcKeyedOutbox.latestMeasure(connection, key, row.snapshot().revision());
        });
    }

    @Override public int acknowledge(Key key, Lease lease, java.util.List<Acknowledgement> acknowledgements) {
        require(key.digest().equals(lease.keyDigest()), "KEY_LEASE_CHANGED");
        if (acknowledgements == null || acknowledgements.size() > 128) throw new IllegalArgumentException("Invalid acknowledgement budget");
        var snapshot = java.util.List.copyOf(acknowledgements);
        var ids = new java.util.HashSet<UUID>();
        for (var ack : snapshot) if (!ids.add(ack.outputId())) throw new IllegalArgumentException("Duplicate acknowledgement");
        return transaction(connection -> {
            var row = load(connection, key, true);
            Instant now = now(connection);
            requireLease(row, lease, now);
            int updated = JdbcKeyedOutbox.acknowledge(connection, key, row.snapshot().revision(), snapshot, now, null);
            requireLease(row, lease, now(connection));
            return updated;
        });
    }

    record PendingAlert(Key key, UUID outputId) { }

    record HistoricalWindow(Key key, String pins, State schedule, Output output) { }
    record CorrectedWindow(Key key, String pins) { }

    UUID appendWindowCorrection(JdbcRuleDeployments.Deployment deployment, HistoricalWindow historical, UUID correction,
            String confirmationDigest, String payload, java.util.List<Output> outputs, RuleCompilationSnapshot.CorrectionCatchup catchup) {
        var key = historical.key();
        require(deployment.account().equals(key.accountId()) && deployment.rule().equals(key.ruleId())
                && deployment.epoch().equals(key.deploymentEpoch()), "WORKER_TARGET_INVALID");
        require(confirmationDigest.equals(VclCanonical.sha256(payload))
                && payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= MAX_STATE_BYTES, "WINDOW_CORRECTION_LIMIT");
        var lease = outputs.isEmpty() ? null : acquire(key, historical.pins(), correction);
        return transaction(connection -> {
            JdbcRuleDeployments.lockExecutable(connection, deployment, outputs.isEmpty());
            var row = load(connection, key, true);
            var original = JdbcKeyedOutbox.find(connection, key, row.snapshot().revision(), historical.output().id())
                    .orElseThrow(() -> new VclException("WINDOW_MEASURE_UNAVAILABLE", ""));
            require(historical.output().equals(original.output()), "WINDOW_MEASURE_CHANGED");
            boolean recorded = false;
            UUID proposal = null;
            try (var query = prepare(connection, """
                    SELECT key_digest,original_measure_id,confirmation_digest,canonical_payload,alert_proposal_id
                    FROM vcl_reviewed_window_corrections WHERE account_id=? AND deployment_epoch=? AND correction_id=?
                    """)) {
                query.setObject(1, key.accountId());
                query.setObject(2, key.deploymentEpoch());
                query.setObject(3, correction);
                try (var rows = query.executeQuery()) {
                    if (rows.next()) {
                        require(key.digest().equals(rows.getString(1)) && original.output().id().equals(rows.getObject(2, UUID.class))
                                && confirmationDigest.equals(rows.getString(3)) && payload.equals(rows.getString(4)),
                                "WINDOW_CORRECTION_CHANGED");
                        recorded = true;
                        proposal = rows.getObject(5, UUID.class);
                    }
                }
            }
            if (!recorded) try (var insert = prepare(connection, """
                    INSERT INTO vcl_reviewed_window_corrections(account_id,deployment_epoch,correction_id,key_digest,
                        original_measure_id,confirmation_digest,canonical_payload,window_start,window_end) VALUES(?,?,?,?,?,?,?,?,?)
                    """)) {
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
                require(insert.executeUpdate() == 1, "WINDOW_CORRECTION_CHANGED");
            }
            if (proposal != null || outputs.isEmpty()) return proposal;
            require(outputs.size() == 2 && outputs.getFirst().kind() == OutputKind.MEASURE
                    && outputs.getLast().kind() == OutputKind.ALERT_PROPOSAL, "WINDOW_CORRECTION_CHANGED");
            requireLease(row, lease, now(connection));
            long revision = row.snapshot().revision();
            var latest = JdbcKeyedOutbox.latestMeasure(connection, key, revision)
                    .orElseThrow(() -> new VclException("WINDOW_MEASURE_UNAVAILABLE", ""));
            require(latest.output().equals(original.output()) && JdbcKeyedTimers.read(connection, key, revision).isEmpty(),
                    "WINDOW_MEASURE_CHANGED");
            requireCorrectedWindowBudget(connection, key);
            var schedule = IntervalWindowSchedule.recipe(row.snapshot().state());
            require(catchup != null && schedule.generation().equals(catchup.generation())
                    && openingCheckpoint(connection, deployment, catchup.generation(), 0) <= catchup.scannedHead(),
                    "WINDOW_CORRECTION_SOURCE_ADVANCED");
            var checkpoint = ALERT_PARSER.readJson(original.output().payload().payload().getBytes(java.nio.charset.StandardCharsets.UTF_8))
                    .path("checkpoint").textValue();
            boolean hasCatchup = catchup.lastRelevant() > com.voltcanary.vcl.VclObservationReadiness.checkpoint(checkpoint);
            var next = hasCatchup ? schedule.advance(Math.max(schedule.minimumCheckpoint(), catchup.lastRelevant()), outputs.getFirst().id())
                    : schedule;
            var timers = hasCatchup ? java.util.List.of(IntervalWindowSchedule.timer(row.pins(), next)) : java.util.List.<Timer>of();
            var transition = new Transition(correction, 1, confirmationDigest, revision, hasCatchup ? next.state() : row.snapshot().state(),
                    timers, null, outputs);
            long committed = apply(connection, key, row, transition);
            JdbcKeyedInbox.insert(connection, key, transition, committed, "OBSERVATION");
            proposal = outputs.getLast().id();
            try (var update = prepare(connection, """
                    UPDATE vcl_reviewed_window_corrections SET alert_proposal_id=?
                    WHERE account_id=? AND deployment_epoch=? AND correction_id=? AND alert_proposal_id IS NULL
                    """)) {
                update.setObject(1, proposal);
                update.setObject(2, key.accountId());
                update.setObject(3, key.deploymentEpoch());
                update.setObject(4, correction);
                require(update.executeUpdate() == 1, "WINDOW_CORRECTION_CHANGED");
            }
            try (var update = prepare(connection, """
                    UPDATE vcl_rule_deployments SET reference_correction_revision=reference_correction_revision+1
                    WHERE account_id=? AND deployment_epoch=? AND reference_correction_revision<?
                    """)) {
                update.setObject(1, key.accountId());
                update.setObject(2, key.deploymentEpoch());
                update.setLong(3, Long.MAX_VALUE);
                require(update.executeUpdate() == 1, "WINDOW_CORRECTION_LIMIT");
            }
            requireLease(row, lease, now(connection));
            return proposal;
        });
    }

    private static void requireCorrectedWindowBudget(Connection connection, Key key) throws SQLException {
        try (var query = prepare(connection, """
                SELECT COUNT(DISTINCT key_digest) FROM vcl_reviewed_window_corrections
                WHERE account_id=? AND deployment_epoch=? AND alert_proposal_id IS NOT NULL AND key_digest<>?
                """)) {
            query.setObject(1, key.accountId());
            query.setObject(2, key.deploymentEpoch());
            query.setString(3, key.digest());
            try (var rows = query.executeQuery()) {
                require(rows.next() && rows.getLong(1) < MAX_CORRECTED_WINDOWS, "WINDOW_CORRECTION_LIMIT");
            }
        }
    }

    java.util.List<CorrectedWindow> correctedWindows(JdbcRuleDeployments.Deployment deployment, Instant from, Instant until) {
        try (var connection = connection(); var query = prepare(connection, """
                SELECT DISTINCT s.subject_digest,s.occurrence_digest,s.key_digest,s.pins_digest
                FROM vcl_reviewed_window_corrections c
                JOIN vcl_keyed_state s ON s.account_id=c.account_id AND s.key_digest=c.key_digest
                WHERE c.account_id=? AND c.deployment_epoch=? AND s.rule_id=? AND s.deployment_epoch=c.deployment_epoch
                    AND c.alert_proposal_id IS NOT NULL AND c.window_start<? AND c.window_end>?
                ORDER BY s.key_digest LIMIT ?
                """)) {
            query.setObject(1, deployment.account());
            query.setObject(2, deployment.epoch());
            query.setObject(3, deployment.rule());
            query.setObject(4, until.atOffset(ZoneOffset.UTC));
            query.setObject(5, from.atOffset(ZoneOffset.UTC));
            query.setInt(6, MAX_CORRECTED_WINDOWS + 1);
            try (var rows = query.executeQuery()) {
                var result = new java.util.ArrayList<CorrectedWindow>();
                while (rows.next()) {
                    var key = new Key(deployment.account(), deployment.rule(), deployment.epoch(), rows.getString(1), rows.getString(2));
                    require(key.digest().equals(rows.getString(3)) && result.size() < MAX_CORRECTED_WINDOWS, "WINDOW_CORRECTION_LIMIT");
                    result.add(new CorrectedWindow(key, rows.getString(4)));
                }
                return java.util.List.copyOf(result);
            }
        } catch (SQLException error) { throw unavailable(); }
    }

    HistoricalWindow historicalWindow(JdbcRuleDeployments.Deployment deployment, UUID measure) {
        try (var connection = connection(); var query = prepare(connection, """
                SELECT s.subject_digest,s.occurrence_digest,s.key_digest
                FROM vcl_keyed_state s JOIN vcl_keyed_outbox o ON o.account_id=s.account_id AND o.key_digest=s.key_digest
                WHERE s.account_id=? AND s.rule_id=? AND s.deployment_epoch=?
                    AND o.output_id=? AND o.output_kind='MEASURE'
                FETCH FIRST 2 ROWS ONLY
                """)) {
            query.setObject(1, deployment.account());
            query.setObject(2, deployment.rule());
            query.setObject(3, deployment.epoch());
            query.setObject(4, measure);
            Key key;
            try (var rows = query.executeQuery()) {
                require(rows.next(), "WINDOW_MEASURE_UNAVAILABLE");
                key = new Key(deployment.account(), deployment.rule(), deployment.epoch(), rows.getString(1), rows.getString(2));
                require(key.digest().equals(rows.getString(3)) && !rows.next(), "WINDOW_MEASURE_CHANGED");
            }
            var row = load(connection, key, false);
            var output = JdbcKeyedOutbox.find(connection, key, row.snapshot().revision(), measure)
                    .orElseThrow(() -> new VclException("WINDOW_MEASURE_UNAVAILABLE", ""));
            require(output.output().kind() == OutputKind.MEASURE, "WINDOW_MEASURE_CHANGED");
            return new HistoricalWindow(key, row.pins(), row.snapshot().state(), output.output());
        } catch (SQLException error) { throw unavailable(); }
    }

    record PendingSustained(Key key, String pins, UUID measure) { }

    private static String sustainedCandidatePattern() {
        return "%\"profile\":\"" + com.voltcanary.analytics.vcl.DurableSustained.PROFILE + "\"%";
    }

    java.util.List<PendingSustained> pendingSustained(JdbcRuleDeployments.Deployment deployment, int limit, int offset) {
        if (limit < 1 || limit > 128 || offset < 0 || offset > 1_000_000) throw new IllegalArgumentException("Invalid measure page");
        try (var connection = connection(); var query = prepare(connection, """
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
                    var key = new Key(deployment.account(), deployment.rule(), deployment.epoch(), rows.getString(1), rows.getString(2));
                    require(key.digest().equals(rows.getString(3)), "KEY_IDENTITY_CHANGED");
                    result.add(new PendingSustained(key, rows.getString(4), rows.getObject(5, UUID.class)));
                }
                return java.util.List.copyOf(result);
            }
        } catch (SQLException error) { throw unavailable(); }
    }

    java.util.List<PendingSustained> pausedSustained(int limit, int offset) {
        if (limit < 1 || limit > 128 || offset < 0 || offset > 1_000_000) throw new IllegalArgumentException("Invalid pause page");
        try (var connection = connection(); var query = prepare(connection, """
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
                    var key = new Key(rows.getObject(1, UUID.class), rows.getObject(2, UUID.class), rows.getObject(3, UUID.class),
                            rows.getString(4), rows.getString(5));
                    require(key.digest().equals(rows.getString(6)), "KEY_IDENTITY_CHANGED");
                    result.add(new PendingSustained(key, rows.getString(7), rows.getObject(8, UUID.class)));
                }
                return java.util.List.copyOf(result);
            }
        } catch (SQLException error) { throw unavailable(); }
    }

    void closePausedSustained(PendingSustained pending, JdbcRuleDeployments.Deployment deployment) {
        var key = pending.key();
        require(deployment.state() == JdbcRuleDeployments.State.PAUSED && key.accountId().equals(deployment.account())
                && key.ruleId().equals(deployment.rule()) && key.deploymentEpoch().equals(deployment.epoch()), "DEPLOYMENT_CHANGED");
        transaction(connection -> {
            JdbcRuleDeployments.lockCurrent(connection, deployment);
            var row = load(connection, key, true);
            require(row.pins().equals(pending.pins()), "KEY_PINS_CHANGED");
            long revision = row.snapshot().revision();
            var source = JdbcKeyedOutbox.find(connection, key, revision, pending.measure())
                    .orElseThrow(() -> new VclException("KEY_OUTPUT_UNAVAILABLE", ""));
            if (source.receiptId() != null) return null;
            var candidate = ALERT_PARSER.readJson(source.output().payload().payload().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            require(source.output().kind() == OutputKind.MEASURE
                    && com.voltcanary.analytics.vcl.DurableSustained.PROFILE.equals(candidate.path("profile").textValue()),
                    "SUSTAINED_MEASURE_CHANGED");
            require(row.inboxCount() < MAX_INBOX && JdbcKeyedTimers.read(connection, key, revision).isEmpty(),
                    "SUSTAINED_STATE_INVALID");
            UUID resultId = SessionAbsenceSchedule.id(pending.pins(), "sustained-final:" + pending.measure());
            var measure = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                    .put("profile", "vcl-sustained-final-measure-1").put("sourceMeasure", pending.measure().toString())
                    .put("occurrence", candidate.path("occurrence").textValue())
                    .put("status", "UNKNOWN").put("reason", "PAUSED").putNull("literal");
            var output = new Output(resultId, OutputKind.MEASURE, null, State.parse(VclCanonical.encode(measure)));
            var transition = new Transition(resultId, 1, source.output().digest(), revision, row.snapshot().state(),
                    java.util.List.of(), null, java.util.List.of(output));
            long committed = apply(connection, key, row, transition);
            JdbcKeyedInbox.insert(connection, key, transition, committed, "OBSERVATION");
            var receipt = new Acknowledgement(pending.measure(), source.output().digest(), resultId, output.digest());
            require(JdbcKeyedOutbox.acknowledge(connection, key, committed, java.util.List.of(receipt), now(connection), OutputKind.MEASURE) == 1,
                    "SUSTAINED_MEASURE_CHANGED");
            return null;
        });
    }

    record PausedWindow(Key key, UUID timerId) { }

    java.util.List<PausedWindow> pausedWindows(int limit, int offset) {
        if (limit < 1 || limit > 128 || offset < 0 || offset > 1_000_000) throw new IllegalArgumentException("Invalid pause page");
        try (var connection = connection(); var query = prepare(connection, """
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
                    var key = new Key(rows.getObject(1, UUID.class), rows.getObject(2, UUID.class), rows.getObject(3, UUID.class),
                            rows.getString(4), rows.getString(5));
                    require(key.digest().equals(rows.getString(6)), "KEY_IDENTITY_CHANGED");
                    result.add(new PausedWindow(key, rows.getObject(7, UUID.class)));
                }
                return java.util.List.copyOf(result);
            }
        } catch (SQLException error) { throw unavailable(); }
    }

    void closePausedWindow(PausedWindow pending, JdbcRuleDeployments.Deployment deployment) {
        var key = pending.key();
        require(deployment.state() == JdbcRuleDeployments.State.PAUSED && key.accountId().equals(deployment.account())
                && key.ruleId().equals(deployment.rule()) && key.deploymentEpoch().equals(deployment.epoch()), "DEPLOYMENT_CHANGED");
        transaction(connection -> {
            JdbcRuleDeployments.lockCurrent(connection, deployment);
            var row = load(connection, key, true);
            long revision = row.snapshot().revision();
            var timers = JdbcKeyedTimers.read(connection, key, revision);
            if (timers.stream().noneMatch(timer -> pending.timerId().equals(timer.timer().id()))) return null;
            var staged = row.snapshot().state();
            require(timers.size() == 1, "WINDOW_TIMER_STATE_CHANGED");
            if (timers.getFirst().timer().kind() == TimerKind.WAIT_TIMEOUT) {
                var recipe = SessionAbsenceSchedule.recipe(new DueTimer(key, row.pins(), timers.getFirst()), staged);
                var measure = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                        .put("profile", SessionAbsenceSchedule.MEASURE).put("node", recipe.node())
                        .put("status", "UNKNOWN").put("reason", "PAUSED").putNull("literal");
                if (recipe.previous() != null) measure.put("supersedes", recipe.previous().toString());
                measure.putArray("evidence");
                writePaused(connection, key, row, staged, staged, State.parse(VclCanonical.encode(measure)));
                return null;
            }
            require(timers.getFirst().timer().payload().equals(staged), "WINDOW_TIMER_STATE_CHANGED");
            if (timers.getFirst().timer().kind() == TimerKind.SESSION_TIMEOUT) {
                var profile = ALERT_PARSER.readJson(staged.payload().getBytes(java.nio.charset.StandardCharsets.UTF_8))
                        .path("profile").textValue();
                var next = com.voltcanary.analytics.vcl.DurableZoneSession.PROFILE.equals(profile)
                        ? com.voltcanary.analytics.vcl.DurableZoneSession.pauseSnapshot(key, staged)
                        : com.voltcanary.analytics.vcl.DurableWindowSession.pauseSnapshot(key, staged);
                writePaused(connection, key, row, staged, next, next);
                return null;
            }
            require(timers.getFirst().timer().kind() == TimerKind.WINDOW_CLOSE, "WINDOW_TIMER_STATE_CHANGED");
            if (IntervalWindowSchedule.handles(staged)) {
                var recipe = IntervalWindowSchedule.recipe(staged);
                var measure = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                        .put("profile", "vcl-interval-final-measure-1").put("node", recipe.node())
                        .put("status", "UNKNOWN").put("reason", "PAUSED").putNull("literal");
                writePaused(connection, key, row, staged, staged, State.parse(VclCanonical.encode(measure)));
                return null;
            }
            DurableJournalWindow.recipe(staged);
            var value = (com.fasterxml.jackson.databind.node.ObjectNode) ALERT_PARSER.readJson(
                    staged.payload().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            require("STAGED".equals(value.path("phase").textValue()), "WINDOW_TIMER_STATE_CHANGED");
            value.put("phase", "PAUSED").put("status", "UNKNOWN").put("reason", "PAUSED").putNull("literal")
                    .put("finalized", false).put("coverage", "UNKNOWN").put("coverageReason", "PAUSED");
            value.putArray("evidence");
            var decision = (com.fasterxml.jackson.databind.node.ObjectNode) value.path("decision");
            decision.put("status", "UNKNOWN").put("reason", "PAUSED").putNull("literal").putArray("evidence");
            var next = State.parse(VclCanonical.encode(value));
            value.remove("recipe");
            writePaused(connection, key, row, staged, next, State.parse(VclCanonical.encode(value)));
            return null;
        });
    }

    private static void writePaused(Connection connection, Key key, Row row, State staged, State next, State measure)
            throws SQLException {
        var output = new Output(pauseId(key, "measure"), OutputKind.MEASURE, null, measure);
        var transition = new Transition(pauseId(key, "control"), 1, staged.digest(), row.snapshot().revision(), next,
                java.util.List.of(), null, java.util.List.of(output));
        long committed = apply(connection, key, row, transition);
        JdbcKeyedInbox.insert(connection, key, transition, committed, "OBSERVATION");
    }

    private static UUID pauseId(Key key, String purpose) {
        return UUID.nameUUIDFromBytes(("VCL_PAUSE/1\n" + key.digest() + "\n" + purpose)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    boolean hasStagedWindow(Key key, String subject, com.voltcanary.analytics.vcl.VclWindowPlan.Window window) {
        try (var connection = connection()) {
            var row = load(connection, key, false);
            if (row.snapshot().revision() == 0) return false;
            var recipe = DurableJournalWindow.recipe(row.snapshot().state());
            require(subject.equals(recipe.subject()) && window.equals(recipe.window()), "WINDOW_RECIPE_INVALID");
            return true;
        } catch (VclException missing) {
            if ("KEY_UNAVAILABLE".equals(missing.code())) return false;
            throw missing;
        } catch (SQLException error) { throw unavailable(); }
    }

    java.util.Optional<com.voltcanary.analytics.vcl.DurableZoneSession.GeometryPin> zonePin(Key key) {
        try (var connection = connection()) {
            var snapshot = load(connection, key, false).snapshot();
            if (snapshot.revision() == 0) return java.util.Optional.empty();
            return java.util.Optional.of(com.voltcanary.analytics.vcl.DurableZoneSession.geometryPin(key, snapshot.state()));
        } catch (VclException missing) {
            if ("KEY_UNAVAILABLE".equals(missing.code())) return java.util.Optional.empty();
            throw missing;
        } catch (SQLException error) { throw unavailable(); }
    }

    boolean hasCommittedState(Key key) {
        try (var connection = connection()) {
            return load(connection, key, false).snapshot().revision() > 0;
        } catch (VclException missing) {
            if ("KEY_UNAVAILABLE".equals(missing.code())) return false;
            throw missing;
        } catch (SQLException error) { throw unavailable(); }
    }

    boolean drainingOccurrence(JdbcRuleDeployments.Deployment deployment, Key key) {
        try (var connection = connection()) {
            return drainingOccurrence(connection, deployment, key);
        } catch (SQLException error) { throw unavailable(); }
    }

    private static boolean drainingOccurrence(Connection connection, JdbcRuleDeployments.Deployment deployment, Key key)
            throws SQLException {
        if (deployment.start() == null || deployment.start().predecessor() == null) return false;
        try (var query = prepare(connection, """
                SELECT 1 FROM vcl_rule_deployments d
                JOIN vcl_keyed_state s ON s.account_id=d.account_id AND s.rule_id=d.rule_id
                    AND s.deployment_epoch=d.deployment_epoch
                WHERE d.account_id=? AND d.rule_id=? AND d.drain_before<=?
                    AND s.subject_digest=? AND s.occurrence_digest=? AND s.state_revision>0
                    AND s.journal_opened_at<d.drain_before
                FETCH FIRST 1 ROW ONLY
                """)) {
            query.setObject(1, key.accountId());
            query.setObject(2, key.ruleId());
            query.setObject(3, deployment.start().boundary().atOffset(ZoneOffset.UTC));
            query.setString(4, key.subjectDigest());
            query.setString(5, key.occurrenceDigest());
            try (var rows = query.executeQuery()) { return rows.next(); }
        }
    }

    long openingCheckpoint(JdbcRuleDeployments.Deployment deployment, UUID generation, long initial) {
        try (var connection = connection()) {
            return openingCheckpoint(connection, deployment, generation, initial);
        } catch (SQLException error) { throw unavailable(); }
    }

    boolean sessionTimeoutReady(JdbcRuleDeployments.Deployment deployment, DueTimer due, UUID generation, long initial, long head) {
        var key = due.key();
        require(key.accountId().equals(deployment.account()) && key.ruleId().equals(deployment.rule())
                && key.deploymentEpoch().equals(deployment.epoch()) && generation != null && head >= initial && initial >= 0,
                "WORKER_TARGET_INVALID");
        return transaction(connection -> {
            JdbcRuleDeployments.lockExecutable(connection, deployment, true);
            var row = load(connection, key, true);
            require(row.pins().equals(due.pinsDigest()) && row.snapshot().revision() == due.scheduled().stateRevision(),
                    "SESSION_TIMER_CHANGED");
            long through = JdbcKeyedTimers.freezeSessionCut(connection, due, generation, head, now(connection));
            return openingCheckpoint(connection, deployment, generation, initial) >= through;
        });
    }

    private static long openingCheckpoint(Connection connection, JdbcRuleDeployments.Deployment deployment,
            UUID generation, long initial) throws SQLException {
        try (var query = prepare(connection, """
                SELECT input_generation,checkpoint FROM vcl_journal_window_cursor WHERE account_id=? AND deployment_epoch=?
                """)) {
            query.setObject(1, deployment.account());
            query.setObject(2, deployment.epoch());
            try (var rows = query.executeQuery()) {
                if (!rows.next()) return initial;
                require(generation.equals(rows.getObject(1, UUID.class)) && rows.getLong(2) >= initial,
                        "WINDOW_INPUT_GENERATION_CHANGED");
                return rows.getLong(2);
            }
        }
    }

    boolean advanceOpeningCheckpoint(JdbcRuleDeployments.Deployment deployment, UUID generation, long initial,
            long expected, long next, Long referenceRevision) {
        require(next > expected && next - expected == 1, "WINDOW_INPUT_CHECKPOINT_CHANGED");
        return transaction(connection -> {
            JdbcRuleDeployments.lockExecutable(connection, deployment, true);
            if (referenceRevision != null && referenceRevision != referenceCorrectionRevision(connection, deployment)) return false;
            if (openingCheckpoint(connection, deployment, generation, initial) != expected) return false;
            try (var update = prepare(connection, """
                    UPDATE vcl_journal_window_cursor SET checkpoint=? WHERE account_id=? AND deployment_epoch=?
                    """)) {
                update.setLong(1, next);
                update.setObject(2, deployment.account());
                update.setObject(3, deployment.epoch());
                if (update.executeUpdate() == 1) return true;
            }
            try (var insert = prepare(connection, """
                    INSERT INTO vcl_journal_window_cursor(account_id,deployment_epoch,input_generation,checkpoint)
                    VALUES(?,?,?,?)
                    """)) {
                insert.setObject(1, deployment.account());
                insert.setObject(2, deployment.epoch());
                insert.setObject(3, generation);
                insert.setLong(4, next);
                insert.executeUpdate();
            }
            return true;
        });
    }

    long referenceCorrectionRevision(JdbcRuleDeployments.Deployment deployment) {
        try (var connection = connection()) {
            return referenceCorrectionRevision(connection, deployment);
        } catch (SQLException error) { throw unavailable(); }
    }

    private static long referenceCorrectionRevision(Connection connection, JdbcRuleDeployments.Deployment deployment) throws SQLException {
        try (var query = prepare(connection, """
                SELECT reference_correction_revision FROM vcl_rule_deployments
                WHERE account_id=? AND deployment_epoch=?
                """)) {
            query.setObject(1, deployment.account());
            query.setObject(2, deployment.epoch());
            try (var rows = query.executeQuery()) {
                require(rows.next(), "DEPLOYMENT_UNAVAILABLE");
                return rows.getLong(1);
            }
        }
    }

    java.util.List<PendingAlert> pendingAlerts(int limit, int offset) {
        if (limit < 1 || limit > 128 || offset < 0 || offset > 1_000_000) throw new IllegalArgumentException("Invalid alert page");
        try (var connection = connection(); var query = prepare(connection, """
                SELECT s.account_id,s.rule_id,s.deployment_epoch,s.subject_digest,s.occurrence_digest,
                    s.key_digest,o.output_id
                FROM vcl_keyed_outbox o JOIN vcl_keyed_state s ON s.account_id=o.account_id AND s.key_digest=o.key_digest
                JOIN vcl_rule_deployments d ON d.account_id=s.account_id AND d.deployment_epoch=s.deployment_epoch
                    AND d.rule_id=s.rule_id
                WHERE o.output_kind='ALERT_PROPOSAL' AND o.acknowledged_at IS NULL
                    AND d.starts_at IS NOT NULL AND (d.state IN ('ACTIVE','REVOKED') OR d.drain_before IS NOT NULL)
                    AND NOT EXISTS (SELECT 1 FROM vcl_keyed_outbox prior WHERE prior.account_id=o.account_id
                        AND prior.key_digest=o.key_digest AND prior.output_kind='ALERT_PROPOSAL'
                        AND prior.acknowledged_at IS NULL AND prior.state_revision<o.state_revision)
                ORDER BY s.account_id,s.key_digest,o.state_revision,o.output_id LIMIT ? OFFSET ?
                """)) {
            query.setInt(1, limit);
            query.setInt(2, offset);
            try (var rows = query.executeQuery()) {
                var result = new java.util.ArrayList<PendingAlert>();
                while (rows.next()) {
                    var key = new Key(rows.getObject(1, UUID.class), rows.getObject(2, UUID.class),
                            rows.getObject(3, UUID.class), rows.getString(4), rows.getString(5));
                    require(key.digest().equals(rows.getString(6)), "KEY_IDENTITY_CHANGED");
                    result.add(new PendingAlert(key, rows.getObject(7, UUID.class)));
                }
                return java.util.List.copyOf(result);
            }
        } catch (SQLException error) { throw unavailable(); }
    }

    void deliverAlert(PendingAlert pending, JdbcRuleDeployments.Deployment deployment,
            java.util.function.BiFunction<Output, Output, Acknowledgement> send) {
        var key = pending.key();
        require(key.accountId().equals(deployment.account()) && key.ruleId().equals(deployment.rule())
                && key.deploymentEpoch().equals(deployment.epoch()) && deployment.start() != null, "WORKER_TARGET_INVALID");
        transaction(connection -> {
            if (deployment.state() == JdbcRuleDeployments.State.REVOKED
                    || deployment.state() == JdbcRuleDeployments.State.PAUSED) {
                JdbcRuleDeployments.lockCurrent(connection, deployment);
            } else {
                JdbcRuleDeployments.lockExecutable(connection, deployment, true);
            }
            var row = load(connection, key, true);
            long revision = row.snapshot().revision();
            var saved = JdbcKeyedOutbox.find(connection, key, revision, pending.outputId())
                    .orElseThrow(() -> new VclException("KEY_OUTPUT_UNAVAILABLE", ""));
            require(saved.output().kind() == OutputKind.ALERT_PROPOSAL, "KEY_OUTPUT_CHANGED");
            if (saved.receiptId() != null) return null;
            try (var prior = prepare(connection, """
                    SELECT 1 FROM vcl_keyed_outbox WHERE account_id=? AND key_digest=?
                        AND output_kind='ALERT_PROPOSAL' AND acknowledged_at IS NULL AND state_revision<?
                    FETCH FIRST 1 ROW ONLY
                    """)) {
                prior.setObject(1, key.accountId());
                prior.setString(2, key.digest());
                prior.setLong(3, saved.stateRevision());
                try (var pendingPrior = prior.executeQuery()) {
                    require(!pendingPrior.next(), "ALERT_PROPOSAL_OUT_OF_ORDER");
                }
            }
            var payload = ALERT_PARSER.readJson(saved.output().payload().payload().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            UUID measureId = UUID.fromString(payload.path("measure").textValue());
            var measure = JdbcKeyedOutbox.find(connection, key, revision, measureId)
                    .orElseThrow(() -> new VclException("KEY_OUTPUT_UNAVAILABLE", ""));
            require(measure.stateRevision() == saved.stateRevision() && measure.output().kind() == OutputKind.MEASURE
                    && measure.output().payload().digest().equals(payload.path("measureDigest").textValue()), "KEY_OUTPUT_CHANGED");
            var acknowledgement = send.apply(saved.output(), measure.output());
            require(saved.output().id().equals(acknowledgement.outputId())
                    && saved.output().digest().equals(acknowledgement.outputDigest()), "KEY_OUTPUT_CHANGED");
            require(JdbcKeyedOutbox.acknowledge(connection, key, revision, java.util.List.of(acknowledgement), now(connection),
                    OutputKind.ALERT_PROPOSAL) == 1, "KEY_OUTPUT_ACK_CONFLICT");
            return null;
        });
    }

    @Override public RekeyReceipt consumeRekey(Key sourceKey, Lease sourceLease, Key targetKey, Lease targetLease, Transition transition) {
        return consumeRekey(sourceKey, sourceLease, targetKey, targetLease, transition, null, null);
    }

    RekeyReceipt consumeWindowRoute(Key sourceKey, Lease sourceLease, Key targetKey, Lease targetLease, Transition transition,
            JdbcRuleDeployments.Deployment deployment, Instant openedAt) {
        require(deployment.account().equals(targetKey.accountId()) && deployment.rule().equals(targetKey.ruleId())
                && deployment.epoch().equals(targetKey.deploymentEpoch()), "WORKER_TARGET_INVALID");
        return consumeRekey(sourceKey, sourceLease, targetKey, targetLease, transition, deployment, openedAt);
    }

    private RekeyReceipt consumeRekey(Key sourceKey, Lease sourceLease, Key targetKey, Lease targetLease, Transition transition,
            JdbcRuleDeployments.Deployment deployment, Instant openedAt) {
        require(sourceKey.accountId().equals(targetKey.accountId()) && sourceKey.ruleId().equals(targetKey.ruleId())
                && sourceKey.deploymentEpoch().equals(targetKey.deploymentEpoch()), "KEY_OUTPUT_TARGET_CHANGED");
        require(sourceKey.digest().equals(sourceLease.keyDigest()) && targetKey.digest().equals(targetLease.keyDigest()), "KEY_LEASE_CHANGED");
        require(sourceLease.pinsDigest().equals(targetLease.pinsDigest()), "KEY_PINS_CHANGED");
        require(transition.consumedTimer() == null, "KEY_REKEY_INPUT_CHANGED");
        boolean sameKey = sourceKey.digest().equals(targetKey.digest());
        require(!sameKey || sourceKey.equals(targetKey), "KEY_IDENTITY_CHANGED");
        return transaction(connection -> {
            if (deployment != null) {
                JdbcRuleDeployments.lockExecutable(connection, deployment, false);
                JdbcRuleDeployments.fenceSession(connection, deployment, openedAt, true);
            }
            // Both directions acquire SQL locks in the same order; RPCs/evaluation never run under these locks.
            boolean sourceFirst = sourceKey.digest().compareTo(targetKey.digest()) <= 0;
            var first = load(connection, sourceFirst ? sourceKey : targetKey, true);
            var second = sameKey ? first : load(connection, sourceFirst ? targetKey : sourceKey, true);
            var origin = sourceFirst ? first : second;
            var target = sourceFirst ? second : first;
            require(origin.pins().equals(sourceLease.pinsDigest()) && target.pins().equals(targetLease.pinsDigest()), "KEY_PINS_CHANGED");
            var output = JdbcKeyedOutbox.find(connection, sourceKey, origin.snapshot().revision(), transition.inputId())
                    .orElseThrow(() -> new VclException("KEY_OUTPUT_UNAVAILABLE", ""));
            require(output.output().kind() == OutputKind.REKEY && targetKey.equals(output.output().target()), "KEY_OUTPUT_TARGET_CHANGED");
            require(transition.inputRevision() == output.stateRevision()
                    && transition.inputDigest().equals(output.output().rekeyInputDigest(sourceKey)), "KEY_REKEY_INPUT_CHANGED");
            var existing = JdbcKeyedRekeys.find(connection, sourceKey, targetKey, output, transition, target.snapshot().revision(), origin.pins());
            if (existing.isPresent()) return existing.get();
            require(output.receiptId() == null, "KEY_OUTPUT_ACK_CONFLICT");
            Instant now = now(connection);
            requireLease(origin, sourceLease, now);
            requireLease(target, targetLease, now);
            apply(connection, targetKey, target, transition);
            if (deployment != null && transition.expectedRevision() == 0) {
                recordJournalOpening(connection, targetKey, openedAt);
            }
            var receipt = JdbcKeyedRekeys.insert(connection, sourceKey, targetKey, output, transition, origin.pins());
            JdbcKeyedOutbox.acknowledge(connection, sourceKey, origin.snapshot().revision(), java.util.List.of(
                    new Acknowledgement(output.output().id(), output.output().digest(), receipt.id(), receipt.digest())), now, OutputKind.REKEY);
            Instant finishedAt = now(connection);
            requireLease(origin, sourceLease, finishedAt);
            requireLease(target, targetLease, finishedAt);
            return receipt;
        });
    }

    private static void recordJournalOpening(Connection connection, Key key, Instant openedAt) throws SQLException {
        try (var update = prepare(connection, """
                UPDATE vcl_keyed_state SET journal_opened_at=?
                WHERE account_id=? AND key_digest=? AND journal_opened_at IS NULL
                """)) {
            update.setObject(1, openedAt.atOffset(ZoneOffset.UTC));
            update.setObject(2, key.accountId());
            update.setString(3, key.digest());
            require(update.executeUpdate() == 1, "WINDOW_OPENING_CHANGED");
        }
    }

    /** State, complete timer schedule and output intents share the caller's locked transaction. */
    private static long apply(Connection connection, Key key, Row row, Transition transition) throws SQLException {
        require(row.snapshot().revision() == transition.expectedRevision(), "KEY_STATE_CONFLICT");
        require(row.inboxCount() < MAX_INBOX, "KEY_INBOX_LIMIT");
        long revision = transition.expectedRevision() + 1;
        try (var update = prepare(connection, "UPDATE vcl_keyed_state SET state_revision=?,state_digest=?,state_payload=?,inbox_count=inbox_count+1 WHERE account_id=? AND key_digest=?")) {
            update.setLong(1, revision);
            update.setString(2, transition.next().digest());
            update.setString(3, transition.next().payload());
            update.setObject(4, key.accountId());
            update.setString(5, key.digest());
            require(update.executeUpdate() == 1, "KEY_UNAVAILABLE");
        }
        JdbcKeyedTimers.replace(connection, key, revision, transition.timers());
        JdbcKeyedOutbox.append(connection, key, revision, transition.outputs());
        return revision;
    }

    private static void requireLease(Row row, Lease lease, Instant now) {
        require(row.pins().equals(lease.pinsDigest()), "KEY_PINS_CHANGED");
        require(lease.holderId().equals(row.holder()) && lease.fence() == row.fence()
                && row.expiry() != null && now.isBefore(row.expiry()), "KEY_LEASE_STALE");
    }

    private record Row(String registration, String pins, Snapshot snapshot, int inboxCount, UUID holder, long fence, Instant expiry) { }

    private Row load(Connection connection, Key key, boolean lock) throws SQLException {
        try (var query = prepare(connection, "SELECT * FROM vcl_keyed_state WHERE account_id=? AND key_digest=?" + (lock ? " FOR UPDATE" : ""))) {
            bindKey(query, key);
            try (var row = query.executeQuery()) {
                require(row.next(), "KEY_UNAVAILABLE");
                require(key.ruleId().equals(row.getObject("rule_id", UUID.class))
                        && key.deploymentEpoch().equals(row.getObject("deployment_epoch", UUID.class))
                        && key.subjectDigest().equals(row.getString("subject_digest"))
                        && key.occurrenceDigest().equals(row.getString("occurrence_digest")), "KEY_IDENTITY_CHANGED");
                require(VERSION.equals(row.getString("state_version")), "KEY_VERSION_UNAVAILABLE");
                var expiry = row.getObject("lease_expires_at", OffsetDateTime.class);
                return new Row(row.getString("registration_digest"), row.getString("pins_digest"),
                        new Snapshot(row.getLong("state_revision"), State.restore(row.getString("state_payload"), row.getString("state_digest"))),
                        row.getInt("inbox_count"), row.getObject("lease_holder", UUID.class), row.getLong("lease_fence"), expiry == null ? null : expiry.toInstant());
            }
        }
    }

    private Connection connection() throws SQLException {
        var connection = source.getConnection();
        if (!connection.getAutoCommit()) {
            connection.close();
            throw new VclException("KEY_AUTOCOMMIT_REQUIRED", "");
        }
        return connection;
    }
    @FunctionalInterface private interface Work<T> { T run(Connection connection) throws SQLException; }
    private <T> T transaction(Work<T> work) {
        try (var connection = connection()) {
            connection.setAutoCommit(false);
            try {
                String database = connection.getMetaData().getDatabaseProductName();
                if (database.equals("PostgreSQL")) {
                    try (var setting = prepare(connection, "SET LOCAL lock_timeout='2s'")) { setting.execute(); }
                } else if (database.equals("H2")) {
                    try (var setting = prepare(connection, "SET LOCK_TIMEOUT 2000")) { setting.execute(); }
                }
                var result = work.run(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException error) {
                try { connection.rollback(); } catch (SQLException ignored) { /* Preserve the original failure. */ }
                throw error;
            }
        } catch (SQLException error) { throw unavailable(); }
    }
    private static void bindKey(java.sql.PreparedStatement statement, Key key) throws SQLException {
        statement.setObject(1, key.accountId());
        statement.setString(2, key.digest());
    }
    Instant databaseTime() {
        return transaction(JdbcKeyedStateStore::now);
    }

    static Instant now(Connection connection) throws SQLException {
        String query = switch (connection.getMetaData().getDatabaseProductName()) {
            case "PostgreSQL" -> "SELECT clock_timestamp()";
            case "H2" -> "SELECT EXECUTING_STATEMENT_START FROM INFORMATION_SCHEMA.SESSIONS WHERE SESSION_ID=SESSION_ID()";
            default -> throw new SQLException("Unsupported execution database clock");
        };
        try (var statement = prepare(connection, query); ResultSet rows = statement.executeQuery()) {
            if (!rows.next() || rows.getObject(1) == null) throw new SQLException("Execution database clock unavailable");
            return rows.getObject(1, OffsetDateTime.class).toInstant();
        }
    }
    static java.sql.PreparedStatement prepare(Connection connection, String sql) throws SQLException {
        var statement = connection.prepareStatement(sql);
        try { statement.setQueryTimeout(5); }
        catch (SQLException failure) { statement.close(); throw failure; }
        return statement;
    }
    private static void require(boolean condition, String code) { if (!condition) throw new VclException(code, ""); }
    private static VclException unavailable() { return new VclException("KEY_STORAGE_UNAVAILABLE", ""); }
}
