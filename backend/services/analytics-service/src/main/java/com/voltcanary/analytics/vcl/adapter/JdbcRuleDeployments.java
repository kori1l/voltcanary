package com.voltcanary.analytics.vcl.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.voltcanary.analytics.vcl.CompilationBundle;
import com.voltcanary.analytics.vcl.CompilationBundleStore;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclParser;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/** Durable deployment identities. Activation requires a fresh owner check; this is not a worker or effect port. */
public final class JdbcRuleDeployments {

    public enum State {
        STAGED,
        ACTIVATING,
        ACTIVE,
        DRAINING,
        PAUSED,
        REVOKED,
    }

    public record CutoverStart(UUID predecessor, Instant boundary, String checkpointDigest) {
        public CutoverStart {
            Objects.requireNonNull(boundary);
            require(boundary.getNano() % 1000 == 0, "DEPLOYMENT_BOUNDARY_INVALID");
            require(
                checkpointDigest != null && checkpointDigest.matches("[0-9a-f]{64}"),
                "DEPLOYMENT_CHECKPOINT_INVALID"
            );
        }
    }

    public record Deployment(
        UUID actor,
        UUID account,
        UUID rule,
        UUID epoch,
        UUID bundle,
        String bundleDigest,
        UUID grant,
        State state,
        long revision,
        Instant drainBefore,
        CutoverStart start,
        Instant resumeFrom
    ) {
        public Deployment(
            UUID actor,
            UUID account,
            UUID rule,
            UUID epoch,
            UUID bundle,
            String bundleDigest,
            UUID grant,
            State state,
            long revision
        ) {
            this(
                actor,
                account,
                rule,
                epoch,
                bundle,
                bundleDigest,
                grant,
                state,
                revision,
                null,
                null,
                null
            );
        }

        boolean acceptsExistingWork() {
            return state == State.ACTIVE || state == State.DRAINING;
        }

        boolean permitsOwnerInspection() {
            return acceptsExistingWork() || state == State.ACTIVATING;
        }

        boolean includesWindow(Instant openedAt) {
            return (
                includesSessionObservation(openedAt) &&
                (drainBefore == null || openedAt.isBefore(drainBefore))
            );
        }

        boolean includesSessionObservation(Instant at) {
            return (
                acceptsExistingWork() &&
                (start == null || !at.isBefore(start.boundary())) &&
                (resumeFrom == null || !at.isBefore(resumeFrom))
            );
        }
    }

    @FunctionalInterface
    public interface FreshAuthority {
        void verify(Deployment deployment);
    }

    @FunctionalInterface
    public interface CutoverAuthority {
        void verify(Deployment previous, Deployment next, CutoverStart start);
    }

    record EvaluatorReadiness(
        String bundleDigest,
        String checkpointDigest,
        String readinessDigest
    ) {
        EvaluatorReadiness {
            for (var digest : new String[] { bundleDigest, checkpointDigest, readinessDigest }) {
                require(
                    digest != null && digest.matches("[0-9a-f]{64}"),
                    "EVALUATOR_READINESS_INVALID"
                );
            }
        }
    }

    private final DataSource source;
    private final CompilationBundleStore bundles;

    public JdbcRuleDeployments(DataSource source, CompilationBundleStore bundles) {
        this.source = Objects.requireNonNull(source);
        this.bundles = Objects.requireNonNull(bundles);
    }

    public Deployment stage(UUID actor, CompilationBundle bundle, UUID epoch) {
        Objects.requireNonNull(actor);
        Objects.requireNonNull(bundle);
        Objects.requireNonNull(epoch);
        var saved = bundles
            .find(bundle.accountId(), bundle.id())
            .orElseThrow(() -> invalid("DEPLOYMENT_BUNDLE_UNAVAILABLE"));
        require(
            saved.digest().equals(bundle.digest()) && saved.payload().equals(bundle.payload()),
            "DEPLOYMENT_BUNDLE_CHANGED"
        );
        var identity = identity(bundle);
        var candidate = new Deployment(
            actor,
            bundle.accountId(),
            identity.rule(),
            epoch,
            bundle.id(),
            bundle.digest(),
            identity.grant(),
            State.STAGED,
            0
        );
        try (var connection = source.getConnection()) {
            require(connection.getAutoCommit(), "DEPLOYMENT_AUTOCOMMIT_REQUIRED");
            try (
                var insert = connection.prepareStatement(
                    """
                    INSERT INTO vcl_rule_deployments(account_id,rule_id,deployment_epoch,bundle_id,bundle_digest,grant_id,actor_id,state)
                    VALUES(?,?,?,?,?,?,?,'STAGED')
                    """
                )
            ) {
                insert.setObject(1, candidate.account());
                insert.setObject(2, candidate.rule());
                insert.setObject(3, candidate.epoch());
                insert.setObject(4, candidate.bundle());
                insert.setString(5, candidate.bundleDigest());
                insert.setObject(6, candidate.grant());
                insert.setObject(7, candidate.actor());
                insert.executeUpdate();
                return candidate;
            }
        } catch (SQLException error) {
            if (!"23505".equals(error.getSQLState())) throw invalid(
                "DEPLOYMENT_STORAGE_UNAVAILABLE"
            );
        }
        var existing = find(candidate.account(), candidate.epoch()).orElseThrow(() ->
            invalid("DEPLOYMENT_STORAGE_UNAVAILABLE")
        );
        require(sameIdentity(existing, candidate), "DEPLOYMENT_EPOCH_REUSED");
        return existing;
    }

    /** A caller must perform fresh owner/grant/bundle admission inside the locked transaction. */
    public Deployment activate(UUID account, UUID epoch, FreshAuthority authority) {
        Objects.requireNonNull(authority);
        try (var connection = source.getConnection()) {
            connection.setAutoCommit(false);
            try {
                var current = locked(connection, account, epoch);
                require(current.state() != State.REVOKED, "DEPLOYMENT_REVOKED");
                require(current.state() != State.PAUSED, "DEPLOYMENT_PAUSED");
                require(current.state() != State.DRAINING, "DEPLOYMENT_DRAINING");
                require(current.state() != State.ACTIVATING, "DEPLOYMENT_ACTIVATING");
                verifyBundle(current);
                authority.verify(current);
                if (current.state() == State.ACTIVE) {
                    connection.commit();
                    return current;
                }
                try (
                    var update = connection.prepareStatement(
                        """
                        UPDATE vcl_rule_deployments SET state='ACTIVE',active_slot=1,revision=revision+1,
                        changed_at=CURRENT_TIMESTAMP WHERE account_id=? AND deployment_epoch=? AND revision=?
                        """
                    )
                ) {
                    update.setObject(1, account);
                    update.setObject(2, epoch);
                    update.setLong(3, current.revision());
                    require(update.executeUpdate() == 1, "DEPLOYMENT_REVISION_CHANGED");
                }
                connection.commit();
                return new Deployment(
                    current.actor(),
                    current.account(),
                    current.rule(),
                    current.epoch(),
                    current.bundle(),
                    current.bundleDigest(),
                    current.grant(),
                    State.ACTIVE,
                    current.revision() + 1
                );
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                if (error instanceof SQLException) throw invalid("DEPLOYMENT_STORAGE_UNAVAILABLE");
                throw error;
            }
        } catch (SQLException error) {
            throw invalid("DEPLOYMENT_STORAGE_UNAVAILABLE");
        }
    }

    public Deployment revoke(UUID account, UUID epoch) {
        try (var connection = source.getConnection()) {
            connection.setAutoCommit(false);
            try {
                var current = locked(connection, account, epoch);
                if (current.state() == State.REVOKED || current.state() == State.PAUSED) {
                    connection.commit();
                    return current;
                }
                try (
                    var update = connection.prepareStatement(
                        """
                        UPDATE vcl_rule_deployments SET state='REVOKED',active_slot=NULL,drain_before=NULL,revision=revision+1,
                        changed_at=CURRENT_TIMESTAMP WHERE account_id=? AND deployment_epoch=? AND revision=?
                        """
                    )
                ) {
                    update.setObject(1, account);
                    update.setObject(2, epoch);
                    update.setLong(3, current.revision());
                    require(update.executeUpdate() == 1, "DEPLOYMENT_REVISION_CHANGED");
                }
                connection.commit();
                return new Deployment(
                    current.actor(),
                    current.account(),
                    current.rule(),
                    current.epoch(),
                    current.bundle(),
                    current.bundleDigest(),
                    current.grant(),
                    State.REVOKED,
                    current.revision() + 1,
                    null,
                    current.start(),
                    current.resumeFrom()
                );
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                if (error instanceof SQLException) throw invalid("DEPLOYMENT_STORAGE_UNAVAILABLE");
                throw error;
            }
        } catch (SQLException error) {
            throw invalid("DEPLOYMENT_STORAGE_UNAVAILABLE");
        }
    }

    /** A confirmed owner pause stops the logical rule, including draining and pending epochs. */
    Deployment pause(UUID account, UUID epoch, FreshAuthority authority) {
        Objects.requireNonNull(authority);
        try (var connection = source.getConnection()) {
            connection.setAutoCommit(false);
            try {
                var current = locked(connection, account, epoch);
                require(
                    current.state() == State.ACTIVE || current.state() == State.PAUSED,
                    "DEPLOYMENT_CHANGED"
                );
                verifyBundle(current);
                authority.verify(current);
                if (current.state() == State.ACTIVE) {
                    try (
                        var update = JdbcKeyedStateStore.prepare(
                            connection,
                            """
                            UPDATE vcl_rule_deployments SET state='REVOKED',active_slot=NULL,drain_before=NULL,
                                paused_at=?,revision=revision+1,changed_at=CURRENT_TIMESTAMP
                            WHERE account_id=? AND rule_id=? AND (state='ACTIVE' OR
                                (state='STAGED' AND (drain_before IS NOT NULL OR starts_at IS NOT NULL)))
                            """
                        )
                    ) {
                        update.setObject(
                            1,
                            JdbcKeyedStateStore.now(connection).atOffset(ZoneOffset.UTC)
                        );
                        update.setObject(2, account);
                        update.setObject(3, current.rule());
                        require(update.executeUpdate() > 0, "DEPLOYMENT_CHANGED");
                    }
                }
                var paused = locked(connection, account, epoch);
                connection.commit();
                return paused;
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                if (failure instanceof SQLException) throw invalid(
                    "DEPLOYMENT_STORAGE_UNAVAILABLE"
                );
                throw failure;
            }
        } catch (SQLException failure) {
            throw invalid("DEPLOYMENT_STORAGE_UNAVAILABLE");
        }
    }

    /** Persist the evaluator-owned ACK once the exact epoch has been prepared for execution. */
    void acknowledgeEvaluatorReadiness(Deployment expected, EvaluatorReadiness readiness) {
        UUID account = expected.account();
        UUID epoch = expected.epoch();
        try (var connection = source.getConnection()) {
            connection.setAutoCommit(false);
            try {
                var current = locked(connection, account, epoch);
                require(expected.equals(current), "DEPLOYMENT_CHANGED");
                require(
                    current.bundleDigest().equals(readiness.bundleDigest()),
                    "DEPLOYMENT_BUNDLE_CHANGED"
                );
                verifyBundle(current);
                var existing = evaluatorReadiness(connection, account, epoch);
                if (existing.isPresent()) {
                    require(existing.get().equals(readiness), "EVALUATOR_READINESS_CHANGED");
                } else {
                    require(current.state() == State.ACTIVATING, "DEPLOYMENT_CHANGED");
                    require(
                        current.start().checkpointDigest().equals(readiness.checkpointDigest()),
                        "EVALUATOR_READINESS_CHANGED"
                    );
                    try (
                        var insert = JdbcKeyedStateStore.prepare(
                            connection,
                            """
                            INSERT INTO vcl_rule_evaluator_readiness(account_id,deployment_epoch,bundle_digest,
                            checkpoint_digest,readiness_digest) VALUES (?,?,?,?,?)
                            """
                        )
                    ) {
                        insert.setObject(1, account);
                        insert.setObject(2, epoch);
                        insert.setString(3, readiness.bundleDigest());
                        insert.setString(4, readiness.checkpointDigest());
                        insert.setString(5, readiness.readinessDigest());
                        require(insert.executeUpdate() == 1, "EVALUATOR_READINESS_CHANGED");
                    }
                }
                connection.commit();
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                if (error instanceof SQLException) throw invalid("DEPLOYMENT_STORAGE_UNAVAILABLE");
                throw error;
            }
        } catch (SQLException error) {
            throw invalid("DEPLOYMENT_STORAGE_UNAVAILABLE");
        }
    }

    List<Deployment> pendingEvaluatorReadiness(int limit, int offset) {
        require(
            limit >= 1 && limit <= 128 && offset >= 0 && offset <= 1_000_000,
            "DEPLOYMENT_SCAN_LIMIT"
        );
        try (
            var connection = source.getConnection();
            var query = JdbcKeyedStateStore.prepare(
                connection,
                """
                SELECT d.* FROM vcl_rule_deployments d WHERE d.state='STAGED' AND d.starts_at IS NOT NULL
                    AND d.drain_before IS NULL AND NOT EXISTS (
                        SELECT 1 FROM vcl_rule_evaluator_readiness r WHERE r.account_id=d.account_id
                            AND r.deployment_epoch=d.deployment_epoch)
                ORDER BY d.account_id,d.deployment_epoch LIMIT ? OFFSET ?
                """
            )
        ) {
            query.setInt(1, limit);
            query.setInt(2, offset);
            try (var rows = query.executeQuery()) {
                var result = new java.util.ArrayList<Deployment>();
                while (rows.next()) result.add(read(rows));
                return List.copyOf(result);
            }
        } catch (SQLException error) {
            throw invalid("DEPLOYMENT_STORAGE_UNAVAILABLE");
        }
    }

    private static Optional<EvaluatorReadiness> evaluatorReadiness(
        Connection connection,
        UUID account,
        UUID epoch
    ) throws SQLException {
        try (
            var query = JdbcKeyedStateStore.prepare(
                connection,
                """
                SELECT bundle_digest,checkpoint_digest,readiness_digest FROM vcl_rule_evaluator_readiness
                WHERE account_id=? AND deployment_epoch=?
                """
            )
        ) {
            query.setObject(1, account);
            query.setObject(2, epoch);
            try (var rows = query.executeQuery()) {
                return rows.next()
                    ? Optional.of(
                          new EvaluatorReadiness(
                              rows.getString(1),
                              rows.getString(2),
                              rows.getString(3)
                          )
                      )
                    : Optional.empty();
            }
        }
    }

    /** Stage or finish a reviewed slot transfer. Only finishing can move the active slot. */
    Deployment cutover(
        UUID account,
        UUID nextEpoch,
        CutoverStart start,
        RuleStartCheckpointRecipe recipe,
        CutoverAuthority authority,
        boolean finish
    ) {
        Objects.requireNonNull(authority);
        require(!nextEpoch.equals(start.predecessor()), "DEPLOYMENT_BOUNDARY_INVALID");
        var inputs = recipe.inputs();
        require(
            start.checkpointDigest().equals(inputs.checkpointDigest()) &&
                start.boundary().equals(inputs.boundary()),
            "START_CHECKPOINT_CHANGED"
        );
        boolean previousFirst =
            start.predecessor() != null && start.predecessor().compareTo(nextEpoch) < 0;
        try (var connection = source.getConnection()) {
            connection.setAutoCommit(false);
            try {
                var previous = previousFirst
                    ? locked(connection, account, start.predecessor())
                    : null;
                var next = locked(connection, account, nextEpoch);
                if (!previousFirst && start.predecessor() != null) {
                    previous = locked(connection, account, start.predecessor());
                }
                require(
                    previous == null || previous.rule().equals(next.rule()),
                    "DEPLOYMENT_CUTOVER_SCOPE_CHANGED"
                );
                var bundle = verifyBundle(next);
                var compiled = bundle.json().path("compilation");
                require(
                    inputs.documentDigest().equals(compiled.path("documentDigest").textValue()),
                    "DEPLOYMENT_BUNDLE_CHANGED"
                );
                if (next.state() == State.ACTIVATING || next.state() == State.ACTIVE) {
                    require(start.equals(next.start()), "START_CHECKPOINT_CHANGED");
                    require(
                        recipe.equals(readStartRecipe(connection, account, nextEpoch)),
                        "START_CHECKPOINT_CHANGED"
                    );
                }
                if (next.state() == State.ACTIVE) {
                    authority.verify(previous, next, start);
                    connection.commit();
                    return next;
                }
                require(
                    next.state() == State.ACTIVATING || (!finish && next.state() == State.STAGED),
                    "DEPLOYMENT_CHANGED"
                );
                verifyPredecessor(connection, previous, next, start.boundary());
                authority.verify(previous, next, start);
                if (!finish) {
                    if (next.state() == State.STAGED) saveStartRecipe(
                        connection,
                        next,
                        start,
                        recipe
                    );
                    var pending = locked(connection, account, nextEpoch);
                    connection.commit();
                    return pending;
                }
                var ready = evaluatorReadiness(connection, account, nextEpoch).orElseThrow(() ->
                    invalid("EVALUATOR_READINESS_UNAVAILABLE")
                );
                require(
                    ready.bundleDigest().equals(next.bundleDigest()) &&
                        ready.checkpointDigest().equals(start.checkpointDigest()),
                    "EVALUATOR_READINESS_CHANGED"
                );
                if (previous != null) {
                    requireNoPendingRoutes(connection, previous);
                    beginDrain(connection, previous, start.boundary());
                }
                try (
                    var activate = JdbcKeyedStateStore.prepare(
                        connection,
                        """
                        UPDATE vcl_rule_deployments SET state='ACTIVE',active_slot=1,resume_from=?,
                        revision=revision+1,changed_at=CURRENT_TIMESTAMP
                        WHERE account_id=? AND deployment_epoch=?
                        """
                    )
                ) {
                    Instant resumed =
                        previous == null && lastPause(connection, next) != null
                            ? JdbcKeyedStateStore.now(connection)
                            : null;
                    activate.setObject(
                        1,
                        resumed == null ? null : resumed.atOffset(ZoneOffset.UTC)
                    );
                    activate.setObject(2, account);
                    activate.setObject(3, nextEpoch);
                    require(activate.executeUpdate() == 1, "DEPLOYMENT_REVISION_CHANGED");
                }
                var activated = locked(connection, account, nextEpoch);
                connection.commit();
                return activated;
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                if (error instanceof SQLException sql && "23505".equals(sql.getSQLState())) {
                    throw invalid("DEPLOYMENT_ACTIVE_SLOT_TAKEN");
                }
                if (error instanceof SQLException) throw invalid("DEPLOYMENT_STORAGE_UNAVAILABLE");
                throw error;
            }
        } catch (SQLException error) {
            throw invalid("DEPLOYMENT_STORAGE_UNAVAILABLE");
        }
    }

    private void verifyPredecessor(
        Connection connection,
        Deployment previous,
        Deployment next,
        Instant boundary
    ) throws SQLException {
        if (previous == null) {
            try (
                var query = JdbcKeyedStateStore.prepare(
                    connection,
                    """
                    SELECT 1 FROM vcl_rule_deployments WHERE account_id=? AND rule_id=? AND active_slot=1
                    """
                )
            ) {
                query.setObject(1, next.account());
                query.setObject(2, next.rule());
                try (var rows = query.executeQuery()) {
                    require(!rows.next(), "DEPLOYMENT_ACTIVE_SLOT_TAKEN");
                }
            }
            Instant pausedAt = lastPause(connection, next);
            require(
                pausedAt == null || !boundary.isBefore(pausedAt),
                "DEPLOYMENT_RESUME_BOUNDARY_INVALID"
            );
            return;
        }
        require(previous.state() == State.ACTIVE, "DEPLOYMENT_CHANGED");
        require(
            previous.start() == null || boundary.isAfter(previous.start().boundary()),
            "DEPLOYMENT_BOUNDARY_INVALID"
        );
        var reservation = pendingCutover(connection, previous.account(), previous.epoch());
        require(
            reservation.isEmpty() || reservation.get().epoch().equals(next.epoch()),
            "DEPLOYMENT_CUTOVER_PENDING"
        );
        verifyUnopenedBoundary(connection, previous, boundary);
        verifyBundle(previous);
    }

    private static Instant lastPause(Connection connection, Deployment next) throws SQLException {
        try (
            var query = JdbcKeyedStateStore.prepare(
                connection,
                """
                SELECT MAX(paused_at) FROM vcl_rule_deployments WHERE account_id=? AND rule_id=?
                """
            )
        ) {
            query.setObject(1, next.account());
            query.setObject(2, next.rule());
            try (var rows = query.executeQuery()) {
                if (!rows.next()) return null;
                var paused = rows.getObject(1, OffsetDateTime.class);
                return paused == null ? null : paused.toInstant();
            }
        }
    }

    private static void requireNoPendingRoutes(
        Connection connection,
        Deployment previous
    ) throws SQLException {
        try (
            var query = JdbcKeyedStateStore.prepare(
                connection,
                """
                SELECT 1 FROM vcl_keyed_state s
                JOIN vcl_keyed_outbox o ON o.account_id=s.account_id AND o.key_digest=s.key_digest
                WHERE s.account_id=? AND s.rule_id=? AND s.deployment_epoch=?
                    AND o.output_kind='REKEY' AND o.acknowledged_at IS NULL
                FETCH FIRST 1 ROW ONLY
                """
            )
        ) {
            query.setObject(1, previous.account());
            query.setObject(2, previous.rule());
            query.setObject(3, previous.epoch());
            try (var rows = query.executeQuery()) {
                require(!rows.next(), "DEPLOYMENT_WINDOW_ROUTE_PENDING");
            }
        }
    }

    private static void beginDrain(
        Connection connection,
        Deployment previous,
        Instant boundary
    ) throws SQLException {
        try (
            var drain = JdbcKeyedStateStore.prepare(
                connection,
                """
                UPDATE vcl_rule_deployments SET state='STAGED',active_slot=NULL,drain_before=?,
                revision=revision+1,changed_at=CURRENT_TIMESTAMP WHERE account_id=? AND deployment_epoch=?
                """
            )
        ) {
            drain.setObject(1, boundary.atOffset(ZoneOffset.UTC));
            drain.setObject(2, previous.account());
            drain.setObject(3, previous.epoch());
            require(drain.executeUpdate() == 1, "DEPLOYMENT_REVISION_CHANGED");
        }
    }

    private static void saveStartRecipe(
        Connection connection,
        Deployment next,
        CutoverStart start,
        RuleStartCheckpointRecipe recipe
    ) throws SQLException {
        try (
            var update = JdbcKeyedStateStore.prepare(
                connection,
                """
                UPDATE vcl_rule_deployments SET starts_at=?,start_checkpoint_digest=?,predecessor_epoch=?,
                revision=revision+1,changed_at=CURRENT_TIMESTAMP WHERE account_id=? AND deployment_epoch=?
                """
            );
            var insert = JdbcKeyedStateStore.prepare(
                connection,
                """
                INSERT INTO vcl_rule_start_checkpoint(account_id,deployment_epoch,recipe_digest,recipe_payload)
                VALUES (?,?,?,?)
                """
            )
        ) {
            update.setObject(1, start.boundary().atOffset(ZoneOffset.UTC));
            update.setString(2, start.checkpointDigest());
            update.setObject(3, start.predecessor());
            update.setObject(4, next.account());
            update.setObject(5, next.epoch());
            require(update.executeUpdate() == 1, "DEPLOYMENT_REVISION_CHANGED");
            insert.setObject(1, next.account());
            insert.setObject(2, next.epoch());
            insert.setString(3, recipe.digest());
            insert.setString(4, recipe.payload());
            require(insert.executeUpdate() == 1, "START_CHECKPOINT_CHANGED");
        }
    }

    RuleStartCheckpointRecipe readStartRecipe(UUID account, UUID epoch) {
        try (var connection = source.getConnection()) {
            return readStartRecipe(connection, account, epoch);
        } catch (SQLException error) {
            throw invalid("DEPLOYMENT_STORAGE_UNAVAILABLE");
        }
    }

    private static RuleStartCheckpointRecipe readStartRecipe(
        Connection connection,
        UUID account,
        UUID epoch
    ) throws SQLException {
        try (
            var query = JdbcKeyedStateStore.prepare(
                connection,
                """
                SELECT recipe_payload,recipe_digest FROM vcl_rule_start_checkpoint WHERE account_id=? AND deployment_epoch=?
                """
            )
        ) {
            query.setObject(1, account);
            query.setObject(2, epoch);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) throw invalid("START_CHECKPOINT_UNAVAILABLE");
                return new RuleStartCheckpointRecipe(rows.getString(1), rows.getString(2));
            }
        }
    }

    public Optional<Deployment> find(UUID account, UUID epoch) {
        try (
            var connection = source.getConnection();
            var query = connection.prepareStatement(
                """
                SELECT *
                FROM vcl_rule_deployments WHERE account_id=? AND deployment_epoch=?
                """
            )
        ) {
            query.setObject(1, Objects.requireNonNull(account));
            query.setObject(2, Objects.requireNonNull(epoch));
            try (var rows = query.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                var value = read(rows);
                verifyBundle(value);
                return Optional.of(value);
            }
        } catch (SQLException error) {
            throw invalid("DEPLOYMENT_STORAGE_UNAVAILABLE");
        }
    }

    /** Discovery is a candidate list, never authorization to execute. */
    public List<Deployment> active(int limit) {
        return activePage(limit, 0);
    }

    /** Bounded ordered page; callers restart at zero after the last page. */
    public List<Deployment> activePage(int limit, int offset) {
        return page(limit, offset, false, false);
    }

    List<Deployment> executablePage(int limit, int offset) {
        return page(limit, offset, true, false);
    }

    List<Deployment> journalPage(int limit, int offset) {
        return page(limit, offset, true, true);
    }

    private List<Deployment> page(
        int limit,
        int offset,
        boolean includeDraining,
        boolean requireSessionTimer
    ) {
        require(
            limit >= 1 && limit <= 128 && offset >= 0 && offset <= 1_000_000,
            "DEPLOYMENT_SCAN_LIMIT"
        );
        String sessionCondition = requireSessionTimer
            ? """
              AND EXISTS (
                  SELECT 1 FROM vcl_keyed_state s
                  JOIN vcl_keyed_timers t ON t.account_id=s.account_id AND t.key_digest=s.key_digest
                  WHERE s.account_id=d.account_id AND s.rule_id=d.rule_id AND s.deployment_epoch=d.deployment_epoch
                      AND t.timer_kind IN ('SESSION_TIMEOUT','WAIT_TIMEOUT')
              )
              """
            : "";
        String sql =
            """
            SELECT d.* FROM vcl_rule_deployments d
            WHERE d.state='ACTIVE' OR (? AND d.drain_before IS NOT NULL
            """ +
            sessionCondition +
            """
            )
            ORDER BY d.account_id,d.rule_id,d.deployment_epoch LIMIT ? OFFSET ?
            """;
        try (
            var connection = source.getConnection();
            var query = connection.prepareStatement(sql)
        ) {
            query.setBoolean(1, includeDraining);
            query.setInt(2, limit);
            query.setInt(3, offset);
            try (var rows = query.executeQuery()) {
                var result = new java.util.ArrayList<Deployment>();
                while (rows.next()) {
                    var value = read(rows);
                    verifyBundle(value);
                    result.add(value);
                }
                return List.copyOf(result);
            }
        } catch (SQLException error) {
            throw invalid("DEPLOYMENT_STORAGE_UNAVAILABLE");
        }
    }

    static void lockExecutable(
        Connection connection,
        Deployment expected,
        boolean continuingWork
    ) throws SQLException {
        require(
            expected.state() == State.ACTIVE ||
                (expected.state() == State.DRAINING && continuingWork),
            "DEPLOYMENT_CHANGED"
        );
        lockCurrent(connection, expected);
    }

    static void lockCurrent(Connection connection, Deployment expected) throws SQLException {
        require(
            expected.equals(locked(connection, expected.account(), expected.epoch())),
            "DEPLOYMENT_CHANGED"
        );
    }

    static boolean fenceJournalWindow(
        Connection connection,
        UUID account,
        UUID rule,
        UUID epoch,
        Instant openedAt,
        boolean consumingTimer,
        boolean finalizing
    ) throws SQLException {
        var deployment = findLocked(connection, account, epoch);
        if (deployment.isEmpty()) {
            require(!finalizing, "DEPLOYMENT_UNAVAILABLE");
            return false; // Standalone diagnostic epoch.
        }
        var current = deployment.get();
        require(
            current.rule().equals(rule) &&
                current.includesWindow(openedAt) &&
                (consumingTimer || current.state() == State.ACTIVE),
            "DEPLOYMENT_CHANGED"
        );
        if (consumingTimer) {
            require((current.start() != null) == finalizing, "WINDOW_FINALIZATION_REQUIRED");
        }
        if (!consumingTimer) {
            var reservation = pendingCutover(connection, account, epoch);
            require(
                reservation.isEmpty() || openedAt.isBefore(reservation.get().boundary()),
                "DEPLOYMENT_CUTOVER_BOUNDARY_RESERVED"
            );
        }
        return true;
    }

    static void fenceSession(
        Connection connection,
        Deployment current,
        Instant openedAt,
        boolean opening
    ) throws SQLException {
        require(current.start() != null && current.includesWindow(openedAt), "DEPLOYMENT_CHANGED");
        if (opening) {
            require(current.state() == State.ACTIVE, "DEPLOYMENT_CHANGED");
            var reservation = pendingCutover(connection, current.account(), current.epoch());
            require(
                reservation.isEmpty() || openedAt.isBefore(reservation.get().boundary()),
                "DEPLOYMENT_CUTOVER_BOUNDARY_RESERVED"
            );
        }
    }

    private record BoundaryReservation(UUID epoch, Instant boundary) {}

    /** Callers hold the predecessor row lock for both reservation and admission. */
    private static Optional<BoundaryReservation> pendingCutover(
        Connection connection,
        UUID account,
        UUID predecessor
    ) throws SQLException {
        try (
            var query = JdbcKeyedStateStore.prepare(
                connection,
                """
                SELECT deployment_epoch,starts_at FROM vcl_rule_deployments
                WHERE account_id=? AND predecessor_epoch=? AND state='STAGED' AND drain_before IS NULL
                FETCH FIRST 2 ROWS ONLY
                """
            )
        ) {
            query.setObject(1, account);
            query.setObject(2, predecessor);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                var reservation = new BoundaryReservation(
                    rows.getObject(1, UUID.class),
                    rows.getObject(2, OffsetDateTime.class).toInstant()
                );
                require(!rows.next(), "DEPLOYMENT_CUTOVER_PENDING");
                return Optional.of(reservation);
            }
        }
    }

    private static void verifyUnopenedBoundary(
        Connection connection,
        Deployment previous,
        Instant boundary
    ) throws SQLException {
        try (
            var query = JdbcKeyedStateStore.prepare(
                connection,
                """
                SELECT state_payload,state_digest,journal_opened_at FROM vcl_keyed_state
                WHERE account_id=? AND rule_id=? AND deployment_epoch=? AND state_revision>0
                    AND (journal_opened_at>=? OR journal_opened_at IS NULL)
                FETCH FIRST 129 ROWS ONLY
                """
            )
        ) {
            query.setObject(1, previous.account());
            query.setObject(2, previous.rule());
            query.setObject(3, previous.epoch());
            query.setObject(4, boundary.atOffset(ZoneOffset.UTC));
            try (var rows = query.executeQuery()) {
                int scanned = 0;
                while (rows.next()) {
                    require(rows.getObject(3) == null, "DEPLOYMENT_BOUNDARY_ALREADY_OPEN");
                    require(++scanned <= 128, "DEPLOYMENT_BOUNDARY_HISTORY_LIMIT");
                    var state = com.voltcanary.analytics.vcl.KeyedStateStore.State.restore(
                        rows.getString(1),
                        rows.getString(2)
                    );
                    var recipe = DurableJournalWindow.recipe(state);
                    require(
                        recipe.window().startInclusive().isBefore(boundary),
                        "DEPLOYMENT_BOUNDARY_ALREADY_OPEN"
                    );
                }
            }
        }
    }

    private static Deployment locked(
        Connection connection,
        UUID account,
        UUID epoch
    ) throws SQLException {
        return findLocked(connection, account, epoch).orElseThrow(() ->
            invalid("DEPLOYMENT_UNAVAILABLE")
        );
    }

    private static Optional<Deployment> findLocked(
        Connection connection,
        UUID account,
        UUID epoch
    ) throws SQLException {
        try (
            var query = JdbcKeyedStateStore.prepare(
                connection,
                """
                SELECT *
                FROM vcl_rule_deployments WHERE account_id=? AND deployment_epoch=? FOR UPDATE
                """
            )
        ) {
            query.setObject(1, Objects.requireNonNull(account));
            query.setObject(2, Objects.requireNonNull(epoch));
            try (var rows = query.executeQuery()) {
                return rows.next() ? Optional.of(read(rows)) : Optional.empty();
            }
        }
    }

    private CompilationBundle verifyBundle(Deployment deployment) {
        var bundle = bundles
            .find(deployment.account(), deployment.bundle())
            .orElseThrow(() -> invalid("DEPLOYMENT_BUNDLE_UNAVAILABLE"));
        require(bundle.digest().equals(deployment.bundleDigest()), "DEPLOYMENT_BUNDLE_CHANGED");
        var identity = identity(bundle);
        require(
            identity.rule().equals(deployment.rule()) &&
                identity.grant().equals(deployment.grant()),
            "DEPLOYMENT_BUNDLE_CHANGED"
        );
        return bundle;
    }

    private record Identity(UUID rule, UUID grant) {}

    private static Identity identity(CompilationBundle bundle) {
        try {
            JsonNode document = bundle.json().path("compilation").path("canonicalDocument");
            var parsed = new VclParser().parse(document.textValue());
            return new Identity(
                UUID.fromString(parsed.spec().path("ruleId").textValue()),
                UUID.fromString(parsed.spec().path("executionGrantId").textValue())
            );
        } catch (RuntimeException error) {
            throw invalid("DEPLOYMENT_BUNDLE_CHANGED");
        }
    }

    private static Deployment read(ResultSet row) throws SQLException {
        try {
            var actor = row.getObject("actor_id", UUID.class);
            if (actor == null) throw invalid("DEPLOYMENT_ACTOR_UNAVAILABLE");
            var drain = row.getObject("drain_before", OffsetDateTime.class);
            var starts = row.getObject("starts_at", OffsetDateTime.class);
            var resumed = row.getObject("resume_from", OffsetDateTime.class);
            var start =
                starts == null
                    ? null
                    : new CutoverStart(
                          row.getObject("predecessor_epoch", UUID.class),
                          starts.toInstant(),
                          row.getString("start_checkpoint_digest")
                      );
            var state = State.valueOf(row.getString("state"));
            if (row.getObject("paused_at") != null) state = State.PAUSED;
            else if (drain != null) state = State.DRAINING;
            else if (state == State.STAGED && start != null) state = State.ACTIVATING;
            return new Deployment(
                actor,
                row.getObject("account_id", UUID.class),
                row.getObject("rule_id", UUID.class),
                row.getObject("deployment_epoch", UUID.class),
                row.getObject("bundle_id", UUID.class),
                row.getString("bundle_digest"),
                row.getObject("grant_id", UUID.class),
                state,
                row.getLong("revision"),
                drain == null ? null : drain.toInstant(),
                start,
                resumed == null ? null : resumed.toInstant()
            );
        } catch (RuntimeException error) {
            throw invalid("DEPLOYMENT_ROW_INVALID");
        }
    }

    private static boolean sameIdentity(Deployment a, Deployment b) {
        return (
            a.actor().equals(b.actor()) &&
            a.account().equals(b.account()) &&
            a.rule().equals(b.rule()) &&
            a.epoch().equals(b.epoch()) &&
            a.bundle().equals(b.bundle()) &&
            a.bundleDigest().equals(b.bundleDigest()) &&
            a.grant().equals(b.grant())
        );
    }

    private static void require(boolean valid, String code) {
        if (!valid) throw invalid(code);
    }

    private static VclException invalid(String code) {
        return new VclException(code, "");
    }
}
