package com.voltcanary.analytics.vcl.adapter;

import static com.voltcanary.analytics.vcl.adapter.JdbcKeyedStateStore.MAX_CORRECTED_WINDOWS;

import com.voltcanary.analytics.vcl.KeyedStateStore.DueTimer;
import com.voltcanary.analytics.vcl.KeyedStateStore.Key;
import com.voltcanary.analytics.vcl.KeyedStateStore.Lease;
import com.voltcanary.analytics.vcl.KeyedStateStore.OutputStatus;
import com.voltcanary.analytics.vcl.KeyedStateStore.TimerKind;
import com.voltcanary.analytics.vcl.adapter.JdbcKeyedStateStore.SessionCandidate;
import com.voltcanary.vcl.VclException;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/** Focused discovery operations sharing the original state-store transaction boundary. */
final class JdbcKeyedDiscovery {

    private final JdbcKeyedStateStore store;

    JdbcKeyedDiscovery(JdbcKeyedStateStore store) {
        this.store = store;
    }

    public java.util.List<DueTimer> dueTimers(UUID accountId, int limit) {
        java.util.Objects.requireNonNull(accountId);
        if (limit < 1 || limit > 128) throw new IllegalArgumentException(
            "Invalid timer discovery limit"
        );
        try (var connection = store.connection()) {
            return JdbcKeyedTimers.due(
                connection,
                accountId,
                limit,
                JdbcKeyedStateStore.now(connection)
            );
        } catch (SQLException error) {
            throw JdbcKeyedStateStore.unavailable();
        }
    }

    public java.util.List<DueTimer> dueTimers(
        UUID accountId,
        UUID ruleId,
        UUID deploymentEpoch,
        int limit
    ) {
        return dueTimers(accountId, ruleId, deploymentEpoch, null, null, limit);
    }

    public java.util.List<DueTimer> dueTimers(
        UUID accountId,
        UUID ruleId,
        UUID deploymentEpoch,
        TimerKind kind,
        DueTimer after,
        int limit
    ) {
        java.util.Objects.requireNonNull(accountId);
        java.util.Objects.requireNonNull(ruleId);
        java.util.Objects.requireNonNull(deploymentEpoch);
        if (limit < 1 || limit > 128) throw new IllegalArgumentException(
            "Invalid timer discovery limit"
        );
        if (
            after != null &&
            (!accountId.equals(after.key().accountId()) ||
                !ruleId.equals(after.key().ruleId()) ||
                !deploymentEpoch.equals(after.key().deploymentEpoch()) ||
                kind != after.scheduled().timer().kind())
        ) {
            throw new IllegalArgumentException("Invalid timer discovery cursor");
        }
        try (var connection = store.connection()) {
            return JdbcKeyedTimers.due(
                connection,
                accountId,
                ruleId,
                deploymentEpoch,
                kind,
                after,
                limit,
                JdbcKeyedStateStore.now(connection)
            );
        } catch (SQLException error) {
            throw JdbcKeyedStateStore.unavailable();
        }
    }

    java.util.Optional<OutputStatus> latestSessionMeasure(Key key, Lease lease) {
        return store.transaction(connection -> {
            var row = store.load(connection, key, true);
            JdbcKeyedStateStore.requireLease(row, lease, JdbcKeyedStateStore.now(connection));
            JdbcKeyedStateStore.require(
                row.snapshot().equals(lease.snapshot()),
                "KEY_STATE_CONFLICT"
            );
            return JdbcKeyedOutbox.latestMeasure(connection, key, row.snapshot().revision());
        });
    }

    java.util.List<SessionCandidate> sessionCandidates(
        JdbcRuleDeployments.Deployment deployment,
        RuleCompilationSnapshot.UnresolvedSessionInput input
    ) {
        var earliest = input.at().minus(input.maximumAgeUs(), java.time.temporal.ChronoUnit.MICROS);
        try (
            var connection = store.connection();
            var query = JdbcKeyedStateStore.prepare(
                connection,
                """
                SELECT subject_digest,occurrence_digest,key_digest,pins_digest,journal_opened_at
                FROM vcl_keyed_state
                WHERE account_id=? AND rule_id=? AND deployment_epoch=? AND state_revision>0
                  AND journal_opened_at>=? AND journal_opened_at<=?
                  AND subject_digest=COALESCE(?,subject_digest) AND occurrence_digest=COALESCE(?,occurrence_digest)
                ORDER BY journal_opened_at,key_digest LIMIT ?
                """
            )
        ) {
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
                    var key = new Key(
                        deployment.account(),
                        deployment.rule(),
                        deployment.epoch(),
                        rows.getString(1),
                        rows.getString(2)
                    );
                    JdbcKeyedStateStore.require(
                        result.size() < MAX_CORRECTED_WINDOWS &&
                            key.digest().equals(rows.getString(3)),
                        "SESSION_CORRECTION_LIMIT"
                    );
                    result.add(
                        new SessionCandidate(
                            key,
                            rows.getString(4),
                            rows.getObject(5, OffsetDateTime.class).toInstant()
                        )
                    );
                }
                return java.util.List.copyOf(result);
            }
        } catch (SQLException error) {
            throw JdbcKeyedStateStore.unavailable();
        }
    }

    java.util.Optional<com.voltcanary.analytics.vcl.DurableZoneSession.GeometryPin> zonePin(
        Key key
    ) {
        try (var connection = store.connection()) {
            var snapshot = store.load(connection, key, false).snapshot();
            if (snapshot.revision() == 0) return java.util.Optional.empty();
            return java.util.Optional.of(
                com.voltcanary.analytics.vcl.DurableZoneSession.geometryPin(key, snapshot.state())
            );
        } catch (VclException missing) {
            if ("KEY_UNAVAILABLE".equals(missing.code())) return java.util.Optional.empty();
            throw missing;
        } catch (SQLException error) {
            throw JdbcKeyedStateStore.unavailable();
        }
    }

    boolean hasCommittedState(Key key) {
        try (var connection = store.connection()) {
            return store.load(connection, key, false).snapshot().revision() > 0;
        } catch (VclException missing) {
            if ("KEY_UNAVAILABLE".equals(missing.code())) return false;
            throw missing;
        } catch (SQLException error) {
            throw JdbcKeyedStateStore.unavailable();
        }
    }
}
