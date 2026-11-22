package com.voltcanary.analytics.vcl.adapter;

import com.voltcanary.analytics.vcl.KeyedStateStore;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclException;

import lombok.RequiredArgsConstructor;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

/**
 * Isolated owner transactions. No Spring bean, activation, external effects or authority is
 * provided.
 */
@RequiredArgsConstructor
public final class JdbcKeyedStateStore implements KeyedStateStore {
    static final int MAX_CORRECTED_WINDOWS = 128;
    static final com.voltcanary.vcl.VclParser ALERT_PARSER = new com.voltcanary.vcl.VclParser();
    final DataSource source;

    @Override
    public Snapshot register(Key key, String pinsDigest, State initial) {
        // Validate the immutable pin using the same public value contract as worker leases.
        if (pinsDigest == null || !pinsDigest.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid execution digest");
        String registration =
                VclCanonical.sha256(
                        VERSION
                                + "\n"
                                + key.digest()
                                + "\n"
                                + pinsDigest
                                + "\n"
                                + initial.digest());
        try (var connection = connection()) {
            try (var insert =
                    prepare(
                            connection,
                            """
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
        } catch (SQLException error) {
            throw unavailable();
        }
    }

    @Override
    public Snapshot inspect(Key key, String pinsDigest) {
        try (var connection = connection()) {
            var row = load(connection, key, false);
            require(row.pins().equals(pinsDigest), "KEY_PINS_CHANGED");
            return row.snapshot();
        } catch (SQLException error) {
            throw unavailable();
        }
    }

    @Override
    public Lease acquire(Key key, String pinsDigest, UUID holderId) {
        java.util.Objects.requireNonNull(holderId);
        return transaction(
                connection -> {
                    var row = load(connection, key, true);
                    require(row.pins().equals(pinsDigest), "KEY_PINS_CHANGED");
                    Instant now = now(connection);
                    require(
                            row.expiry() == null
                                    || !now.isBefore(row.expiry())
                                    || holderId.equals(row.holder()),
                            "KEY_LEASE_BUSY");
                    require(row.fence() < Long.MAX_VALUE, "KEY_FENCE_LIMIT");
                    long fence = row.fence() + 1;
                    Instant expiry = now.plusSeconds(30);
                    try (var update =
                            prepare(
                                    connection,
                                    "UPDATE vcl_keyed_state SET"
                                        + " lease_holder=?,lease_fence=?,lease_expires_at=? WHERE"
                                        + " account_id=? AND key_digest=?")) {
                        update.setObject(1, holderId);
                        update.setLong(2, fence);
                        update.setObject(3, expiry.atOffset(ZoneOffset.UTC));
                        update.setObject(4, key.accountId());
                        update.setString(5, key.digest());
                        require(update.executeUpdate() == 1, "KEY_UNAVAILABLE");
                    }
                    return new Lease(
                            holderId,
                            fence,
                            key.digest(),
                            pinsDigest,
                            expiry,
                            row.snapshot(),
                            JdbcKeyedTimers.read(connection, key, row.snapshot().revision()));
                });
    }

    @Override
    public Receipt commit(Key key, Lease lease, Transition transition) {
        return commit(key, lease, transition, new KeyedCommit.Isolated());
    }

    Receipt commitJournalWindow(
            Key key,
            Lease lease,
            Transition transition,
            Instant openedAt,
            JdbcRuleDeployments.Deployment deployment,
            JdbcJournalWatermarks.Candidate watermark) {
        return commit(
                key,
                lease,
                transition,
                new KeyedCommit.JournalWindow(
                        java.util.Optional.ofNullable(deployment),
                        openedAt,
                        java.util.Optional.ofNullable(watermark)));
    }

    Receipt commitSession(
            Key key,
            Lease lease,
            Transition transition,
            Instant openedAt,
            JdbcRuleDeployments.Deployment deployment) {
        return commit(key, lease, transition, new KeyedCommit.Session(deployment, openedAt));
    }

    Receipt commitReviewedInput(
            Key key,
            Lease lease,
            Transition transition,
            Instant openedAt,
            JdbcRuleDeployments.Deployment deployment) {
        return commit(key, lease, transition, new KeyedCommit.ReviewedInput(deployment, openedAt));
    }

    Receipt commitSustainedFinalization(
            Key key,
            Lease lease,
            Transition transition,
            Instant openedAt,
            JdbcRuleDeployments.Deployment deployment,
            Acknowledgement measure) {
        return commit(
                key,
                lease,
                transition,
                new KeyedCommit.SustainedFinalization(deployment, openedAt, measure));
    }

    Receipt commitCoveredAggregate(
            Key key,
            Lease lease,
            Transition transition,
            Instant openedAt,
            JdbcRuleDeployments.Deployment deployment,
            JdbcJournalWatermarks.Candidate watermark) {
        return commit(
                key,
                lease,
                transition,
                new KeyedCommit.CoveredAggregate(
                        deployment, java.util.Optional.ofNullable(openedAt), watermark));
    }

    private Receipt commit(Key key, Lease lease, Transition transition, KeyedCommit context) {
        var deployment = context.deployment().orElse(null);
        var openedAt = context.opening().orElse(null);
        var watermark = context.watermark().orElse(null);
        var measure = context.acknowledgement().orElse(null);
        boolean session = context.requiresSessionFence();
        boolean sessionOccurrence = context instanceof KeyedCommit.Session;
        require(key.digest().equals(lease.keyDigest()), "KEY_LEASE_CHANGED");
        if (deployment != null) {
            require(
                    key.accountId().equals(deployment.account())
                            && key.ruleId().equals(deployment.rule())
                            && key.deploymentEpoch().equals(deployment.epoch()),
                    "WORKER_TARGET_INVALID");
        }
        return transaction(
                connection -> {
                    if (deployment != null)
                        JdbcRuleDeployments.lockExecutable(
                                connection,
                                deployment,
                                transition.consumedTimer() != null
                                        || measure != null
                                        || sessionOccurrence && transition.expectedRevision() > 0);
                    if (session) {
                        boolean opening = transition.expectedRevision() == 0;
                        JdbcRuleDeployments.fenceSession(connection, deployment, openedAt, opening);
                        if (opening && sessionOccurrence) {
                            require(
                                    !drainingOccurrence(connection, deployment, key),
                                    "SESSION_OCCURRENCE_DRAINING");
                        }
                    }
                    boolean deployed =
                            !session
                                    && openedAt != null
                                    && JdbcRuleDeployments.fenceJournalWindow(
                                            connection,
                                            key.accountId(),
                                            key.ruleId(),
                                            key.deploymentEpoch(),
                                            openedAt,
                                            transition.consumedTimer() != null,
                                            watermark != null && watermark.finalizing() != null);
                    var row = load(connection, key, true);
                    require(row.pins().equals(lease.pinsDigest()), "KEY_PINS_CHANGED");
                    String inputKind = transition.consumedTimer() == null ? "OBSERVATION" : "TIMER";
                    var existing =
                            JdbcKeyedInbox.find(
                                    connection,
                                    key,
                                    row.snapshot().revision(),
                                    inputKind,
                                    transition.inputId(),
                                    transition.inputRevision());
                    if (existing.isPresent()) {
                        var receipt = existing.get();
                        require(
                                receipt.transitionDigest().equals(transition.digest()),
                                "KEY_INPUT_CONFLICT");
                        require(
                                receipt.committedRevision() == transition.expectedRevision() + 1,
                                "KEY_RECEIPT_CORRUPT");
                        return new Receipt(receipt.committedRevision(), true);
                    }
                    Instant now = now(connection);
                    requireLease(row, lease, now);
                    require(
                            row.snapshot().revision() == transition.expectedRevision(),
                            "KEY_STATE_CONFLICT");
                    require(row.inboxCount() < MAX_INBOX, "KEY_INBOX_LIMIT");
                    if (transition.consumedTimer() != null) {
                        var cut =
                                JdbcKeyedTimers.requireDue(
                                        connection, key, transition, now, sessionOccurrence);
                        if (cut != null) {
                            require(
                                    openingCheckpoint(connection, deployment, cut.generation(), 0)
                                            >= cut.checkpoint(),
                                    "SESSION_INPUT_PENDING");
                        }
                    }
                    long revision = apply(connection, key, row, transition);
                    if (openedAt != null
                            && transition.consumedTimer() == null
                            && (!session || transition.expectedRevision() == 0)) {
                        recordJournalOpening(connection, key, openedAt);
                    }
                    JdbcKeyedInbox.insert(connection, key, transition, revision, inputKind);
                    if (measure != null) {
                        require(
                                JdbcKeyedOutbox.acknowledge(
                                                connection,
                                                key,
                                                revision,
                                                java.util.List.of(measure),
                                                now,
                                                OutputKind.MEASURE)
                                        == 1,
                                "SUSTAINED_MEASURE_CHANGED");
                    }
                    if ((deployed || session) && watermark != null) {
                        require(transition.consumedTimer() != null, "WINDOW_WATERMARK_INVALID");
                        JdbcJournalWatermarks.advance(connection, key, watermark);
                        JdbcJournalWatermarks.requireFinalizationFrontier(
                                connection, key, watermark);
                    }
                    requireLease(row, lease, now(connection));
                    return new Receipt(revision, false);
                });
    }

    @Override
    public java.util.List<DueTimer> dueTimers(UUID accountId, int limit) {
        java.util.Objects.requireNonNull(accountId);
        if (limit < 1 || limit > 128)
            throw new IllegalArgumentException("Invalid timer discovery limit");
        try (var connection = connection()) {
            return JdbcKeyedTimers.due(connection, accountId, limit, now(connection));
        } catch (SQLException error) {
            throw unavailable();
        }
    }

    @Override
    public java.util.List<DueTimer> dueTimers(
            UUID accountId, UUID ruleId, UUID deploymentEpoch, int limit) {
        return dueTimers(accountId, ruleId, deploymentEpoch, null, null, limit);
    }

    @Override
    public java.util.List<DueTimer> dueTimers(
            UUID accountId,
            UUID ruleId,
            UUID deploymentEpoch,
            TimerKind kind,
            DueTimer after,
            int limit) {
        java.util.Objects.requireNonNull(accountId);
        java.util.Objects.requireNonNull(ruleId);
        java.util.Objects.requireNonNull(deploymentEpoch);
        if (limit < 1 || limit > 128)
            throw new IllegalArgumentException("Invalid timer discovery limit");
        if (after != null
                && (!accountId.equals(after.key().accountId())
                        || !ruleId.equals(after.key().ruleId())
                        || !deploymentEpoch.equals(after.key().deploymentEpoch())
                        || kind != after.scheduled().timer().kind())) {
            throw new IllegalArgumentException("Invalid timer discovery cursor");
        }
        try (var connection = connection()) {
            return JdbcKeyedTimers.due(
                    connection,
                    accountId,
                    ruleId,
                    deploymentEpoch,
                    kind,
                    after,
                    limit,
                    now(connection));
        } catch (SQLException error) {
            throw unavailable();
        }
    }

    @Override
    public java.util.Optional<InputReceipt> inputReceipt(
            Key key, String pinsDigest, InputKind kind, UUID inputId, long inputRevision) {
        java.util.Objects.requireNonNull(kind);
        java.util.Objects.requireNonNull(inputId);
        if (inputRevision < 1) throw new IllegalArgumentException("Invalid input revision");
        return transaction(
                connection -> {
                    var row = load(connection, key, true);
                    require(row.pins().equals(pinsDigest), "KEY_PINS_CHANGED");
                    return JdbcKeyedInbox.find(
                            connection,
                            key,
                            row.snapshot().revision(),
                            kind.name(),
                            inputId,
                            inputRevision);
                });
    }

    @Override
    public java.util.List<OutputStatus> pendingOutputs(Key key, String pinsDigest, int limit) {
        if (limit < 1 || limit > 128)
            throw new IllegalArgumentException("Invalid outbox read limit");
        return transaction(
                connection -> {
                    var row = load(connection, key, true);
                    require(row.pins().equals(pinsDigest), "KEY_PINS_CHANGED");
                    return JdbcKeyedOutbox.pending(
                            connection, key, row.snapshot().revision(), limit);
                });
    }

    @Override
    public OutputStatus inspectOutput(Key key, String pinsDigest, UUID outputId) {
        java.util.Objects.requireNonNull(outputId);
        return transaction(
                connection -> {
                    var row = load(connection, key, true);
                    require(row.pins().equals(pinsDigest), "KEY_PINS_CHANGED");
                    return JdbcKeyedOutbox.find(
                                    connection, key, row.snapshot().revision(), outputId)
                            .orElseThrow(() -> new VclException("KEY_OUTPUT_UNAVAILABLE", ""));
                });
    }

    java.util.Optional<OutputStatus> latestSessionMeasure(Key key, Lease lease) {
        return transaction(
                connection -> {
                    var row = load(connection, key, true);
                    requireLease(row, lease, now(connection));
                    require(row.snapshot().equals(lease.snapshot()), "KEY_STATE_CONFLICT");
                    return JdbcKeyedOutbox.latestMeasure(
                            connection, key, row.snapshot().revision());
                });
    }

    @Override
    public int acknowledge(Key key, Lease lease, java.util.List<Acknowledgement> acknowledgements) {
        require(key.digest().equals(lease.keyDigest()), "KEY_LEASE_CHANGED");
        if (acknowledgements == null || acknowledgements.size() > 128)
            throw new IllegalArgumentException("Invalid acknowledgement budget");
        var snapshot = java.util.List.copyOf(acknowledgements);
        var ids = new java.util.HashSet<UUID>();
        for (var ack : snapshot)
            if (!ids.add(ack.outputId()))
                throw new IllegalArgumentException("Duplicate acknowledgement");
        return transaction(
                connection -> {
                    var row = load(connection, key, true);
                    Instant now = now(connection);
                    requireLease(row, lease, now);
                    int updated =
                            JdbcKeyedOutbox.acknowledge(
                                    connection,
                                    key,
                                    row.snapshot().revision(),
                                    snapshot,
                                    now,
                                    null);
                    requireLease(row, lease, now(connection));
                    return updated;
                });
    }

    record PendingAlert(Key key, UUID outputId) {}

    record HistoricalWindow(Key key, String pins, State schedule, Output output) {}

    record HistoricalMeasureCandidate(UUID id, State state) {}

    List<HistoricalMeasureCandidate> finalizedIntervalMeasures(
            JdbcRuleDeployments.Deployment deployment, UUID after) {
        return new JdbcKeyedCorrections(this).finalizedIntervalMeasures(deployment, after);
    }

    record CorrectedWindow(Key key, String pins) {}

    UUID appendWindowCorrection(
            JdbcRuleDeployments.Deployment deployment,
            HistoricalWindow historical,
            UUID correction,
            String confirmationDigest,
            String payload,
            java.util.List<Output> outputs,
            RuleCompilationSnapshot.CorrectionCatchup catchup) {
        return new JdbcKeyedCorrections(this)
                .appendWindowCorrection(
                        deployment,
                        historical,
                        correction,
                        confirmationDigest,
                        payload,
                        outputs,
                        catchup);
    }

    static void requireCorrectedWindowBudget(Connection connection, Key key) throws SQLException {
        JdbcKeyedCorrections.requireCorrectedWindowBudget(connection, key);
    }

    java.util.List<CorrectedWindow> correctedWindows(
            JdbcRuleDeployments.Deployment deployment, Instant from, Instant until) {
        return new JdbcKeyedCorrections(this).correctedWindows(deployment, from, until);
    }

    java.util.List<CorrectedWindow> registeredWindows(
            JdbcRuleDeployments.Deployment deployment, java.util.List<String> occurrences) {
        return new JdbcKeyedCorrections(this).registeredWindows(deployment, occurrences);
    }

    record SessionCandidate(Key key, String pins, Instant openedAt) {}

    java.util.List<SessionCandidate> sessionCandidates(
            JdbcRuleDeployments.Deployment deployment,
            RuleCompilationSnapshot.UnresolvedSessionInput input) {
        var earliest = input.at().minus(input.maximumAgeUs(), java.time.temporal.ChronoUnit.MICROS);
        try (var connection = connection();
                var query =
                        prepare(
                                connection,
                                """
                                SELECT subject_digest,occurrence_digest,key_digest,pins_digest,journal_opened_at
                                FROM vcl_keyed_state
                                WHERE account_id=? AND rule_id=? AND deployment_epoch=? AND state_revision>0
                                  AND journal_opened_at>=? AND journal_opened_at<=?
                                  AND subject_digest=COALESCE(?,subject_digest) AND occurrence_digest=COALESCE(?,occurrence_digest)
                                ORDER BY journal_opened_at,key_digest LIMIT ?
                                """)) {
            query.setObject(1, deployment.account());
            query.setObject(2, deployment.rule());
            query.setObject(3, deployment.epoch());
            query.setObject(4, earliest.atOffset(ZoneOffset.UTC));
            query.setObject(5, input.at().atOffset(ZoneOffset.UTC));
            query.setString(6, input.subject());
            query.setString(7, input.occurrence());
            query.setInt(8, MAX_CORRECTED_WINDOWS + 1);
            try (var rows = query.executeQuery()) {
                var result = new java.util.ArrayList<SessionCandidate>();
                while (rows.next()) {
                    var key =
                            new Key(
                                    deployment.account(),
                                    deployment.rule(),
                                    deployment.epoch(),
                                    rows.getString(1),
                                    rows.getString(2));
                    require(
                            result.size() < MAX_CORRECTED_WINDOWS
                                    && key.digest().equals(rows.getString(3)),
                            "SESSION_CORRECTION_LIMIT");
                    result.add(
                            new SessionCandidate(
                                    key,
                                    rows.getString(4),
                                    rows.getObject(5, OffsetDateTime.class).toInstant()));
                }
                return java.util.List.copyOf(result);
            }
        } catch (SQLException error) {
            throw unavailable();
        }
    }

    HistoricalWindow historicalWindow(JdbcRuleDeployments.Deployment deployment, UUID measure) {
        return new JdbcKeyedCorrections(this).historicalWindow(deployment, measure);
    }

    record PendingSustained(Key key, String pins, UUID measure) {}

    private static String sustainedCandidatePattern() {
        return JdbcPausedRuleState.sustainedCandidatePattern();
    }

    java.util.List<PendingSustained> pendingSustained(
            JdbcRuleDeployments.Deployment deployment, int limit, int offset) {
        return new JdbcPausedRuleState(this).pendingSustained(deployment, limit, offset);
    }

    java.util.List<PendingSustained> pausedSustained(int limit, int offset) {
        return new JdbcPausedRuleState(this).pausedSustained(limit, offset);
    }

    void closePausedSustained(PendingSustained pending, JdbcRuleDeployments.Deployment deployment) {
        new JdbcPausedRuleState(this).closePausedSustained(pending, deployment);
    }

    record PausedWindow(Key key, UUID timerId) {}

    java.util.List<PausedWindow> pausedWindows(int limit, int offset) {
        return new JdbcPausedRuleState(this).pausedWindows(limit, offset);
    }

    void closePausedWindow(PausedWindow pending, JdbcRuleDeployments.Deployment deployment) {
        new JdbcPausedRuleState(this).closePausedWindow(pending, deployment);
    }

    static void writePaused(
            Connection connection, Key key, Row row, State staged, State next, State measure)
            throws SQLException {
        JdbcPausedRuleState.writePaused(connection, key, row, staged, next, measure);
    }

    private static UUID pauseId(Key key, String purpose) {
        return JdbcPausedRuleState.pauseId(key, purpose);
    }

    java.util.Optional<com.voltcanary.analytics.vcl.DurableZoneSession.GeometryPin> zonePin(
            Key key) {
        try (var connection = connection()) {
            var snapshot = load(connection, key, false).snapshot();
            if (snapshot.revision() == 0) return java.util.Optional.empty();
            return java.util.Optional.of(
                    com.voltcanary.analytics.vcl.DurableZoneSession.geometryPin(
                            key, snapshot.state()));
        } catch (VclException missing) {
            if ("KEY_UNAVAILABLE".equals(missing.code())) return java.util.Optional.empty();
            throw missing;
        } catch (SQLException error) {
            throw unavailable();
        }
    }

    boolean hasCommittedState(Key key) {
        try (var connection = connection()) {
            return load(connection, key, false).snapshot().revision() > 0;
        } catch (VclException missing) {
            if ("KEY_UNAVAILABLE".equals(missing.code())) return false;
            throw missing;
        } catch (SQLException error) {
            throw unavailable();
        }
    }

    boolean drainingOccurrence(JdbcRuleDeployments.Deployment deployment, Key key) {
        try (var connection = connection()) {
            return drainingOccurrence(connection, deployment, key);
        } catch (SQLException error) {
            throw unavailable();
        }
    }

    static boolean drainingOccurrence(
            Connection connection, JdbcRuleDeployments.Deployment deployment, Key key)
            throws SQLException {
        if (deployment.start() == null || deployment.start().predecessor() == null) return false;
        try (var query =
                prepare(
                        connection,
                        """
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
            try (var rows = query.executeQuery()) {
                return rows.next();
            }
        }
    }

    long openingCheckpoint(
            JdbcRuleDeployments.Deployment deployment, UUID generation, long initial) {
        return new JdbcOpeningCheckpoints(this).openingCheckpoint(deployment, generation, initial);
    }

    boolean sessionTimeoutReady(
            JdbcRuleDeployments.Deployment deployment,
            DueTimer due,
            UUID generation,
            long initial,
            long head) {
        return new JdbcOpeningCheckpoints(this)
                .sessionTimeoutReady(deployment, due, generation, initial, head);
    }

    static long openingCheckpoint(
            Connection connection,
            JdbcRuleDeployments.Deployment deployment,
            UUID generation,
            long initial)
            throws SQLException {
        return JdbcOpeningCheckpoints.openingCheckpoint(
                connection, deployment, generation, initial);
    }

    boolean advanceOpeningCheckpoint(
            JdbcRuleDeployments.Deployment deployment,
            UUID generation,
            long initial,
            long expected,
            long next,
            Long referenceRevision) {
        return new JdbcOpeningCheckpoints(this)
                .advanceOpeningCheckpoint(
                        deployment, generation, initial, expected, next, referenceRevision);
    }

    long referenceCorrectionRevision(JdbcRuleDeployments.Deployment deployment) {
        return new JdbcOpeningCheckpoints(this).referenceCorrectionRevision(deployment);
    }

    static long referenceCorrectionRevision(
            Connection connection, JdbcRuleDeployments.Deployment deployment) throws SQLException {
        return JdbcOpeningCheckpoints.referenceCorrectionRevision(connection, deployment);
    }

    java.util.List<PendingAlert> pendingAlerts(int limit, int offset) {
        if (limit < 1 || limit > 128 || offset < 0 || offset > 1_000_000)
            throw new IllegalArgumentException("Invalid alert page");
        try (var connection = connection();
                var query =
                        prepare(
                                connection,
                                """
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
                    var key =
                            new Key(
                                    rows.getObject(1, UUID.class),
                                    rows.getObject(2, UUID.class),
                                    rows.getObject(3, UUID.class),
                                    rows.getString(4),
                                    rows.getString(5));
                    require(key.digest().equals(rows.getString(6)), "KEY_IDENTITY_CHANGED");
                    result.add(new PendingAlert(key, rows.getObject(7, UUID.class)));
                }
                return java.util.List.copyOf(result);
            }
        } catch (SQLException error) {
            throw unavailable();
        }
    }

    void deliverAlert(
            PendingAlert pending,
            JdbcRuleDeployments.Deployment deployment,
            java.util.function.BiFunction<Output, Output, Acknowledgement> send) {
        var key = pending.key();
        require(
                key.accountId().equals(deployment.account())
                        && key.ruleId().equals(deployment.rule())
                        && key.deploymentEpoch().equals(deployment.epoch())
                        && deployment.start() != null,
                "WORKER_TARGET_INVALID");
        transaction(
                connection -> {
                    if (deployment.state() == JdbcRuleDeployments.State.REVOKED
                            || deployment.state() == JdbcRuleDeployments.State.PAUSED) {
                        JdbcRuleDeployments.lockCurrent(connection, deployment);
                    } else {
                        JdbcRuleDeployments.lockExecutable(connection, deployment, true);
                    }
                    var row = load(connection, key, true);
                    long revision = row.snapshot().revision();
                    var saved =
                            JdbcKeyedOutbox.find(connection, key, revision, pending.outputId())
                                    .orElseThrow(
                                            () -> new VclException("KEY_OUTPUT_UNAVAILABLE", ""));
                    require(
                            saved.output().kind() == OutputKind.ALERT_PROPOSAL,
                            "KEY_OUTPUT_CHANGED");
                    if (saved.receiptId() != null) return null;
                    try (var prior =
                            prepare(
                                    connection,
                                    """
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
                    var payload =
                            ALERT_PARSER.readJson(
                                    saved.output()
                                            .payload()
                                            .payload()
                                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    UUID measureId = UUID.fromString(payload.path("measure").textValue());
                    var measure =
                            JdbcKeyedOutbox.find(connection, key, revision, measureId)
                                    .orElseThrow(
                                            () -> new VclException("KEY_OUTPUT_UNAVAILABLE", ""));
                    require(
                            measure.stateRevision() == saved.stateRevision()
                                    && measure.output().kind() == OutputKind.MEASURE
                                    && measure.output()
                                            .payload()
                                            .digest()
                                            .equals(payload.path("measureDigest").textValue()),
                            "KEY_OUTPUT_CHANGED");
                    var acknowledgement = send.apply(saved.output(), measure.output());
                    require(
                            saved.output().id().equals(acknowledgement.outputId())
                                    && saved.output()
                                            .digest()
                                            .equals(acknowledgement.outputDigest()),
                            "KEY_OUTPUT_CHANGED");
                    require(
                            JdbcKeyedOutbox.acknowledge(
                                            connection,
                                            key,
                                            revision,
                                            java.util.List.of(acknowledgement),
                                            now(connection),
                                            OutputKind.ALERT_PROPOSAL)
                                    == 1,
                            "KEY_OUTPUT_ACK_CONFLICT");
                    return null;
                });
    }

    @Override
    public RekeyReceipt consumeRekey(
            Key sourceKey,
            Lease sourceLease,
            Key targetKey,
            Lease targetLease,
            Transition transition) {
        return consumeRekey(sourceKey, sourceLease, targetKey, targetLease, transition, null, null);
    }

    RekeyReceipt consumeWindowRoute(
            Key sourceKey,
            Lease sourceLease,
            Key targetKey,
            Lease targetLease,
            Transition transition,
            JdbcRuleDeployments.Deployment deployment,
            Instant openedAt) {
        require(
                deployment.account().equals(targetKey.accountId())
                        && deployment.rule().equals(targetKey.ruleId())
                        && deployment.epoch().equals(targetKey.deploymentEpoch()),
                "WORKER_TARGET_INVALID");
        return consumeRekey(
                sourceKey, sourceLease, targetKey, targetLease, transition, deployment, openedAt);
    }

    private RekeyReceipt consumeRekey(
            Key sourceKey,
            Lease sourceLease,
            Key targetKey,
            Lease targetLease,
            Transition transition,
            JdbcRuleDeployments.Deployment deployment,
            Instant openedAt) {
        require(
                sourceKey.accountId().equals(targetKey.accountId())
                        && sourceKey.ruleId().equals(targetKey.ruleId())
                        && sourceKey.deploymentEpoch().equals(targetKey.deploymentEpoch()),
                "KEY_OUTPUT_TARGET_CHANGED");
        require(
                sourceKey.digest().equals(sourceLease.keyDigest())
                        && targetKey.digest().equals(targetLease.keyDigest()),
                "KEY_LEASE_CHANGED");
        require(sourceLease.pinsDigest().equals(targetLease.pinsDigest()), "KEY_PINS_CHANGED");
        require(transition.consumedTimer() == null, "KEY_REKEY_INPUT_CHANGED");
        boolean sameKey = sourceKey.digest().equals(targetKey.digest());
        require(!sameKey || sourceKey.equals(targetKey), "KEY_IDENTITY_CHANGED");
        return transaction(
                connection -> {
                    if (deployment != null) {
                        JdbcRuleDeployments.lockExecutable(connection, deployment, false);
                        JdbcRuleDeployments.fenceSession(connection, deployment, openedAt, true);
                    }
                    // Both directions acquire SQL locks in the same order; RPCs/evaluation never
                    // run under these locks.
                    boolean sourceFirst = sourceKey.digest().compareTo(targetKey.digest()) <= 0;
                    var first = load(connection, sourceFirst ? sourceKey : targetKey, true);
                    var second =
                            sameKey
                                    ? first
                                    : load(connection, sourceFirst ? targetKey : sourceKey, true);
                    var origin = sourceFirst ? first : second;
                    var target = sourceFirst ? second : first;
                    require(
                            origin.pins().equals(sourceLease.pinsDigest())
                                    && target.pins().equals(targetLease.pinsDigest()),
                            "KEY_PINS_CHANGED");
                    var output =
                            JdbcKeyedOutbox.find(
                                            connection,
                                            sourceKey,
                                            origin.snapshot().revision(),
                                            transition.inputId())
                                    .orElseThrow(
                                            () -> new VclException("KEY_OUTPUT_UNAVAILABLE", ""));
                    require(
                            output.output().kind() == OutputKind.REKEY
                                    && targetKey.equals(output.output().target()),
                            "KEY_OUTPUT_TARGET_CHANGED");
                    require(
                            transition.inputRevision() == output.stateRevision()
                                    && transition
                                            .inputDigest()
                                            .equals(output.output().rekeyInputDigest(sourceKey)),
                            "KEY_REKEY_INPUT_CHANGED");
                    var existing =
                            JdbcKeyedRekeys.find(
                                    connection,
                                    sourceKey,
                                    targetKey,
                                    output,
                                    transition,
                                    target.snapshot().revision(),
                                    origin.pins());
                    if (existing.isPresent()) return existing.get();
                    require(output.receiptId() == null, "KEY_OUTPUT_ACK_CONFLICT");
                    Instant now = now(connection);
                    requireLease(origin, sourceLease, now);
                    requireLease(target, targetLease, now);
                    apply(connection, targetKey, target, transition);
                    if (deployment != null && transition.expectedRevision() == 0) {
                        recordJournalOpening(connection, targetKey, openedAt);
                    }
                    var receipt =
                            JdbcKeyedRekeys.insert(
                                    connection,
                                    sourceKey,
                                    targetKey,
                                    output,
                                    transition,
                                    origin.pins());
                    JdbcKeyedOutbox.acknowledge(
                            connection,
                            sourceKey,
                            origin.snapshot().revision(),
                            java.util.List.of(
                                    new Acknowledgement(
                                            output.output().id(),
                                            output.output().digest(),
                                            receipt.id(),
                                            receipt.digest())),
                            now,
                            OutputKind.REKEY);
                    Instant finishedAt = now(connection);
                    requireLease(origin, sourceLease, finishedAt);
                    requireLease(target, targetLease, finishedAt);
                    return receipt;
                });
    }

    static void recordJournalOpening(Connection connection, Key key, Instant openedAt)
            throws SQLException {
        try (var update =
                prepare(
                        connection,
                        """
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
    static long apply(Connection connection, Key key, Row row, Transition transition)
            throws SQLException {
        require(row.snapshot().revision() == transition.expectedRevision(), "KEY_STATE_CONFLICT");
        require(row.inboxCount() < MAX_INBOX, "KEY_INBOX_LIMIT");
        long revision = transition.expectedRevision() + 1;
        try (var update =
                prepare(
                        connection,
                        "UPDATE vcl_keyed_state SET"
                            + " state_revision=?,state_digest=?,state_payload=?,inbox_count=inbox_count+1"
                            + " WHERE account_id=? AND key_digest=?")) {
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

    static void requireLease(Row row, Lease lease, Instant now) {
        require(row.pins().equals(lease.pinsDigest()), "KEY_PINS_CHANGED");
        require(
                lease.holderId().equals(row.holder())
                        && lease.fence() == row.fence()
                        && row.expiry() != null
                        && now.isBefore(row.expiry()),
                "KEY_LEASE_STALE");
    }

    record Row(
            String registration,
            String pins,
            Snapshot snapshot,
            int inboxCount,
            UUID holder,
            long fence,
            Instant expiry) {}

    Row load(Connection connection, Key key, boolean lock) throws SQLException {
        try (var query =
                prepare(
                        connection,
                        "SELECT * FROM vcl_keyed_state WHERE account_id=? AND key_digest=?"
                                + (lock ? " FOR UPDATE" : ""))) {
            bindKey(query, key);
            try (var row = query.executeQuery()) {
                require(row.next(), "KEY_UNAVAILABLE");
                require(
                        key.ruleId().equals(row.getObject("rule_id", UUID.class))
                                && key.deploymentEpoch()
                                        .equals(row.getObject("deployment_epoch", UUID.class))
                                && key.subjectDigest().equals(row.getString("subject_digest"))
                                && key.occurrenceDigest()
                                        .equals(row.getString("occurrence_digest")),
                        "KEY_IDENTITY_CHANGED");
                require(VERSION.equals(row.getString("state_version")), "KEY_VERSION_UNAVAILABLE");
                var expiry = row.getObject("lease_expires_at", OffsetDateTime.class);
                return new Row(
                        row.getString("registration_digest"),
                        row.getString("pins_digest"),
                        new Snapshot(
                                row.getLong("state_revision"),
                                State.restore(
                                        row.getString("state_payload"),
                                        row.getString("state_digest"))),
                        row.getInt("inbox_count"),
                        row.getObject("lease_holder", UUID.class),
                        row.getLong("lease_fence"),
                        expiry == null ? null : expiry.toInstant());
            }
        }
    }

    Connection connection() throws SQLException {
        var connection = source.getConnection();
        if (!connection.getAutoCommit()) {
            connection.close();
            throw new VclException("KEY_AUTOCOMMIT_REQUIRED", "");
        }
        return connection;
    }

    @FunctionalInterface
    interface Work<T> {
        T run(Connection connection) throws SQLException;
    }

    <T> T transaction(Work<T> work) {
        try (var connection = connection()) {
            connection.setAutoCommit(false);
            try {
                String database = connection.getMetaData().getDatabaseProductName();
                if (database.equals("PostgreSQL")) {
                    try (var setting = prepare(connection, "SET LOCAL lock_timeout='2s'")) {
                        setting.execute();
                    }
                } else if (database.equals("H2")) {
                    try (var setting = prepare(connection, "SET LOCK_TIMEOUT 2000")) {
                        setting.execute();
                    }
                }
                var result = work.run(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException error) {
                try {
                    connection.rollback();
                } catch (SQLException ignored) {
                    /* Preserve the original failure. */
                }
                throw error;
            }
        } catch (SQLException error) {
            throw unavailable();
        }
    }

    private static void bindKey(java.sql.PreparedStatement statement, Key key) throws SQLException {
        statement.setObject(1, key.accountId());
        statement.setString(2, key.digest());
    }

    Instant databaseTime() {
        return transaction(JdbcKeyedStateStore::now);
    }

    static Instant now(Connection connection) throws SQLException {
        String query =
                switch (connection.getMetaData().getDatabaseProductName()) {
                    case "PostgreSQL" -> "SELECT clock_timestamp()";
                    case "H2" ->
                            "SELECT EXECUTING_STATEMENT_START FROM INFORMATION_SCHEMA.SESSIONS"
                                    + " WHERE SESSION_ID=SESSION_ID()";
                    default -> throw new SQLException("Unsupported execution database clock");
                };
        try (var statement = prepare(connection, query);
                ResultSet rows = statement.executeQuery()) {
            if (!rows.next() || rows.getObject(1) == null)
                throw new SQLException("Execution database clock unavailable");
            return rows.getObject(1, OffsetDateTime.class).toInstant();
        }
    }

    static java.sql.PreparedStatement prepare(Connection connection, String sql)
            throws SQLException {
        var statement = connection.prepareStatement(sql);
        try {
            statement.setQueryTimeout(5);
        } catch (SQLException failure) {
            statement.close();
            throw failure;
        }
        return statement;
    }

    static void require(boolean condition, String code) {
        if (!condition) throw new VclException(code, "");
    }

    static VclException unavailable() {
        return new VclException("KEY_STORAGE_UNAVAILABLE", "");
    }
}
