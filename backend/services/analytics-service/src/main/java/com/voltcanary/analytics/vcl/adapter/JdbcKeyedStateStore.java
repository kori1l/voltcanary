package com.voltcanary.analytics.vcl.adapter;

import com.voltcanary.analytics.vcl.KeyedStateStore;
import com.voltcanary.vcl.VclException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;

/**
 * Isolated owner transactions. No Spring bean, activation, external effects or authority is
 * provided.
 */
@RequiredArgsConstructor
public final class JdbcKeyedStateStore implements KeyedStateStore {

    static final int MAX_CORRECTED_WINDOWS = 128;
    static final com.voltcanary.vcl.VclParser ALERT_PARSER = new com.voltcanary.vcl.VclParser();
    final DataSource source;
    private final JdbcKeyedDiscovery discovery;
    private final JdbcKeyedDelivery delivery;
    private final JdbcKeyedRekeyTransactions rekeyTransactions;
    private final JdbcKeyedLeases leases;
    private final JdbcKeyedTransitions transitions;
    private final JdbcKeyedTransactions transactions;

    public JdbcKeyedStateStore(DataSource source) {
        this.source = source;
        discovery = new JdbcKeyedDiscovery(this);
        delivery = new JdbcKeyedDelivery(this);
        rekeyTransactions = new JdbcKeyedRekeyTransactions(this);
        leases = new JdbcKeyedLeases(this);
        transitions = new JdbcKeyedTransitions(this);
        transactions = new JdbcKeyedTransactions(source);
    }

    @Override
    public Snapshot register(Key key, String pinsDigest, State initial) {
        return leases.register(key, pinsDigest, initial);
    }

    @Override
    public Snapshot inspect(Key key, String pinsDigest) {
        return leases.inspect(key, pinsDigest);
    }

    @Override
    public Lease acquire(Key key, String pinsDigest, UUID holderId) {
        return leases.acquire(key, pinsDigest, holderId);
    }

    @Override
    public Receipt commit(Key key, Lease lease, Transition transition) {
        return transitions.commit(key, lease, transition);
    }

    Receipt commitJournalWindow(
        Key key,
        Lease lease,
        Transition transition,
        Instant openedAt,
        JdbcRuleDeployments.Deployment deployment,
        JdbcJournalWatermarks.Candidate watermark
    ) {
        return transitions.commitJournalWindow(
            key,
            lease,
            transition,
            openedAt,
            deployment,
            watermark
        );
    }

    Receipt commitSession(
        Key key,
        Lease lease,
        Transition transition,
        Instant openedAt,
        JdbcRuleDeployments.Deployment deployment
    ) {
        return transitions.commitSession(key, lease, transition, openedAt, deployment);
    }

    Receipt commitReviewedInput(
        Key key,
        Lease lease,
        Transition transition,
        Instant openedAt,
        JdbcRuleDeployments.Deployment deployment
    ) {
        return transitions.commitReviewedInput(key, lease, transition, openedAt, deployment);
    }

    Receipt commitSustainedFinalization(
        Key key,
        Lease lease,
        Transition transition,
        Instant openedAt,
        JdbcRuleDeployments.Deployment deployment,
        Acknowledgement measure
    ) {
        return transitions.commitSustainedFinalization(
            key,
            lease,
            transition,
            openedAt,
            deployment,
            measure
        );
    }

    Receipt commitCoveredAggregate(
        Key key,
        Lease lease,
        Transition transition,
        Instant openedAt,
        JdbcRuleDeployments.Deployment deployment,
        JdbcJournalWatermarks.Candidate watermark
    ) {
        return transitions.commitCoveredAggregate(
            key,
            lease,
            transition,
            openedAt,
            deployment,
            watermark
        );
    }

    @Override
    public java.util.List<DueTimer> dueTimers(UUID accountId, int limit) {
        return discovery.dueTimers(accountId, limit);
    }

    @Override
    public java.util.List<DueTimer> dueTimers(
        UUID accountId,
        UUID ruleId,
        UUID deploymentEpoch,
        int limit
    ) {
        return discovery.dueTimers(accountId, ruleId, deploymentEpoch, limit);
    }

    @Override
    public java.util.List<DueTimer> dueTimers(
        UUID accountId,
        UUID ruleId,
        UUID deploymentEpoch,
        TimerKind kind,
        DueTimer after,
        int limit
    ) {
        return discovery.dueTimers(accountId, ruleId, deploymentEpoch, kind, after, limit);
    }

    @Override
    public java.util.Optional<InputReceipt> inputReceipt(
        Key key,
        String pinsDigest,
        InputKind kind,
        UUID inputId,
        long inputRevision
    ) {
        java.util.Objects.requireNonNull(kind);
        java.util.Objects.requireNonNull(inputId);
        if (inputRevision < 1) throw new IllegalArgumentException("Invalid input revision");
        return transaction(connection -> {
            var row = load(connection, key, true);
            require(row.pins().equals(pinsDigest), "KEY_PINS_CHANGED");
            return JdbcKeyedInbox.find(
                connection,
                key,
                row.snapshot().revision(),
                kind.name(),
                inputId,
                inputRevision
            );
        });
    }

    @Override
    public java.util.List<OutputStatus> pendingOutputs(Key key, String pinsDigest, int limit) {
        return delivery.pendingOutputs(key, pinsDigest, limit);
    }

    @Override
    public OutputStatus inspectOutput(Key key, String pinsDigest, UUID outputId) {
        return delivery.inspectOutput(key, pinsDigest, outputId);
    }

    java.util.Optional<OutputStatus> latestSessionMeasure(Key key, Lease lease) {
        return discovery.latestSessionMeasure(key, lease);
    }

    @Override
    public int acknowledge(Key key, Lease lease, java.util.List<Acknowledgement> acknowledgements) {
        return delivery.acknowledge(key, lease, acknowledgements);
    }

    record PendingAlert(Key key, UUID outputId) {}

    record HistoricalWindow(Key key, String pins, State schedule, Output output) {}

    record HistoricalMeasureCandidate(UUID id, State state) {}

    List<HistoricalMeasureCandidate> finalizedIntervalMeasures(
        JdbcRuleDeployments.Deployment deployment,
        UUID after
    ) {
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
        RuleCompilationSnapshot.CorrectionCatchup catchup
    ) {
        return new JdbcKeyedCorrections(this).appendWindowCorrection(
            deployment,
            historical,
            correction,
            confirmationDigest,
            payload,
            outputs,
            catchup
        );
    }

    static void requireCorrectedWindowBudget(Connection connection, Key key) throws SQLException {
        JdbcKeyedCorrections.requireCorrectedWindowBudget(connection, key);
    }

    java.util.List<CorrectedWindow> correctedWindows(
        JdbcRuleDeployments.Deployment deployment,
        Instant from,
        Instant until
    ) {
        return new JdbcKeyedCorrections(this).correctedWindows(deployment, from, until);
    }

    java.util.List<CorrectedWindow> registeredWindows(
        JdbcRuleDeployments.Deployment deployment,
        java.util.List<String> occurrences
    ) {
        return new JdbcKeyedCorrections(this).registeredWindows(deployment, occurrences);
    }

    record SessionCandidate(Key key, String pins, Instant openedAt) {}

    java.util.List<SessionCandidate> sessionCandidates(
        JdbcRuleDeployments.Deployment deployment,
        RuleCompilationSnapshot.UnresolvedSessionInput input
    ) {
        return discovery.sessionCandidates(deployment, input);
    }

    HistoricalWindow historicalWindow(JdbcRuleDeployments.Deployment deployment, UUID measure) {
        return new JdbcKeyedCorrections(this).historicalWindow(deployment, measure);
    }

    record PendingSustained(Key key, String pins, UUID measure) {}

    private static String sustainedCandidatePattern() {
        return JdbcPausedRuleState.sustainedCandidatePattern();
    }

    java.util.List<PendingSustained> pendingSustained(
        JdbcRuleDeployments.Deployment deployment,
        int limit,
        int offset
    ) {
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
        Connection connection,
        Key key,
        Row row,
        State staged,
        State next,
        State measure
    ) throws SQLException {
        JdbcPausedRuleState.writePaused(connection, key, row, staged, next, measure);
    }

    private static UUID pauseId(Key key, String purpose) {
        return JdbcPausedRuleState.pauseId(key, purpose);
    }

    java.util.Optional<com.voltcanary.analytics.vcl.DurableZoneSession.GeometryPin> zonePin(
        Key key
    ) {
        return discovery.zonePin(key);
    }

    boolean hasCommittedState(Key key) {
        return discovery.hasCommittedState(key);
    }

    boolean drainingOccurrence(JdbcRuleDeployments.Deployment deployment, Key key) {
        try (var connection = connection()) {
            return drainingOccurrence(connection, deployment, key);
        } catch (SQLException error) {
            throw unavailable();
        }
    }

    static boolean drainingOccurrence(
        Connection connection,
        JdbcRuleDeployments.Deployment deployment,
        Key key
    ) throws SQLException {
        if (deployment.start() == null || deployment.start().predecessor() == null) return false;
        try (
            var query = prepare(
                connection,
                """
                SELECT 1 FROM vcl_rule_deployments d
                JOIN vcl_keyed_state s ON s.account_id=d.account_id AND s.rule_id=d.rule_id
                    AND s.deployment_epoch=d.deployment_epoch
                WHERE d.account_id=? AND d.rule_id=? AND d.drain_before<=?
                    AND s.subject_digest=? AND s.occurrence_digest=? AND s.state_revision>0
                    AND s.journal_opened_at<d.drain_before
                FETCH FIRST 1 ROW ONLY
                """
            )
        ) {
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
        JdbcRuleDeployments.Deployment deployment,
        UUID generation,
        long initial
    ) {
        return new JdbcOpeningCheckpoints(this).openingCheckpoint(deployment, generation, initial);
    }

    boolean sessionTimeoutReady(
        JdbcRuleDeployments.Deployment deployment,
        DueTimer due,
        UUID generation,
        long initial,
        long head
    ) {
        return new JdbcOpeningCheckpoints(this).sessionTimeoutReady(
            deployment,
            due,
            generation,
            initial,
            head
        );
    }

    static long openingCheckpoint(
        Connection connection,
        JdbcRuleDeployments.Deployment deployment,
        UUID generation,
        long initial
    ) throws SQLException {
        return JdbcOpeningCheckpoints.openingCheckpoint(
            connection,
            deployment,
            generation,
            initial
        );
    }

    boolean advanceOpeningCheckpoint(
        JdbcRuleDeployments.Deployment deployment,
        UUID generation,
        long initial,
        long expected,
        long next,
        Long referenceRevision
    ) {
        return new JdbcOpeningCheckpoints(this).advanceOpeningCheckpoint(
            deployment,
            generation,
            initial,
            expected,
            next,
            referenceRevision
        );
    }

    long referenceCorrectionRevision(JdbcRuleDeployments.Deployment deployment) {
        return new JdbcOpeningCheckpoints(this).referenceCorrectionRevision(deployment);
    }

    static long referenceCorrectionRevision(
        Connection connection,
        JdbcRuleDeployments.Deployment deployment
    ) throws SQLException {
        return JdbcOpeningCheckpoints.referenceCorrectionRevision(connection, deployment);
    }

    java.util.List<PendingAlert> pendingAlerts(int limit, int offset) {
        return delivery.pendingAlerts(limit, offset);
    }

    void deliverAlert(
        PendingAlert pending,
        JdbcRuleDeployments.Deployment deployment,
        java.util.function.BiFunction<Output, Output, Acknowledgement> send
    ) {
        delivery.deliverAlert(pending, deployment, send);
    }

    @Override
    public RekeyReceipt consumeRekey(
        Key sourceKey,
        Lease sourceLease,
        Key targetKey,
        Lease targetLease,
        Transition transition
    ) {
        return rekeyTransactions.consumeRekey(
            sourceKey,
            sourceLease,
            targetKey,
            targetLease,
            transition
        );
    }

    RekeyReceipt consumeWindowRoute(
        Key sourceKey,
        Lease sourceLease,
        Key targetKey,
        Lease targetLease,
        Transition transition,
        JdbcRuleDeployments.Deployment deployment,
        Instant openedAt
    ) {
        return rekeyTransactions.consumeWindowRoute(
            sourceKey,
            sourceLease,
            targetKey,
            targetLease,
            transition,
            deployment,
            openedAt
        );
    }

    static void recordJournalOpening(
        Connection connection,
        Key key,
        Instant openedAt
    ) throws SQLException {
        try (
            var update = prepare(
                connection,
                """
                UPDATE vcl_keyed_state SET journal_opened_at=?
                WHERE account_id=? AND key_digest=? AND journal_opened_at IS NULL
                """
            )
        ) {
            update.setObject(1, openedAt.atOffset(ZoneOffset.UTC));
            update.setObject(2, key.accountId());
            update.setString(3, key.digest());
            require(update.executeUpdate() == 1, "WINDOW_OPENING_CHANGED");
        }
    }

    /** State, complete timer schedule and output intents share the caller's locked transaction. */
    static long apply(
        Connection connection,
        Key key,
        Row row,
        Transition transition
    ) throws SQLException {
        require(row.snapshot().revision() == transition.expectedRevision(), "KEY_STATE_CONFLICT");
        require(row.inboxCount() < MAX_INBOX, "KEY_INBOX_LIMIT");
        long revision = transition.expectedRevision() + 1;
        try (
            var update = prepare(
                connection,
                "UPDATE vcl_keyed_state SET" +
                    " state_revision=?,state_digest=?,state_payload=?,inbox_count=inbox_count+1" +
                    " WHERE account_id=? AND key_digest=?"
            )
        ) {
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
            lease.holderId().equals(row.holder()) &&
                lease.fence() == row.fence() &&
                row.expiry() != null &&
                now.isBefore(row.expiry()),
            "KEY_LEASE_STALE"
        );
    }

    record Row(
        String registration,
        String pins,
        Snapshot snapshot,
        int inboxCount,
        UUID holder,
        long fence,
        Instant expiry
    ) {}

    Row load(Connection connection, Key key, boolean lock) throws SQLException {
        return transactions.load(connection, key, lock);
    }

    Connection connection() throws SQLException {
        return transactions.connection();
    }

    @FunctionalInterface
    interface Work<T> {
        T run(Connection connection) throws SQLException;
    }

    <T> T transaction(Work<T> work) {
        return transactions.transaction(work);
    }

    static void bindKey(java.sql.PreparedStatement statement, Key key) throws SQLException {
        JdbcKeyedTransactions.bindKey(statement, key);
    }

    Instant databaseTime() {
        return transactions.databaseTime();
    }

    static Instant now(Connection connection) throws SQLException {
        return JdbcKeyedTransactions.now(connection);
    }

    static java.sql.PreparedStatement prepare(
        Connection connection,
        String sql
    ) throws SQLException {
        return JdbcKeyedTransactions.prepare(connection, sql);
    }

    static void require(boolean condition, String code) {
        JdbcKeyedTransactions.require(condition, code);
    }

    static VclException unavailable() {
        return JdbcKeyedTransactions.unavailable();
    }
}
