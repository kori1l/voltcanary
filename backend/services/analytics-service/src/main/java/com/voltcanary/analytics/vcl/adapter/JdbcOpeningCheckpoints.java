package com.voltcanary.analytics.vcl.adapter;

import com.voltcanary.analytics.vcl.KeyedStateStore.DueTimer;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

final class JdbcOpeningCheckpoints {

    private final JdbcKeyedStateStore ownerContext;

    JdbcOpeningCheckpoints(JdbcKeyedStateStore ownerContext) {
        this.ownerContext = ownerContext;
    }

    long openingCheckpoint(
        JdbcRuleDeployments.Deployment deployment,
        UUID generation,
        long initial
    ) {
        try (var connection = ownerContext.connection()) {
            return openingCheckpoint(connection, deployment, generation, initial);
        } catch (SQLException error) {
            throw JdbcKeyedStateStore.unavailable();
        }
    }

    boolean sessionTimeoutReady(
        JdbcRuleDeployments.Deployment deployment,
        DueTimer due,
        UUID generation,
        long initial,
        long head
    ) {
        var key = due.key();
        JdbcKeyedStateStore.require(
            key.accountId().equals(deployment.account()) &&
                key.ruleId().equals(deployment.rule()) &&
                key.deploymentEpoch().equals(deployment.epoch()) &&
                generation != null &&
                head >= initial &&
                initial >= 0,
            "WORKER_TARGET_INVALID"
        );
        return ownerContext.transaction(connection -> {
            JdbcRuleDeployments.lockExecutable(connection, deployment, true);
            var row = ownerContext.load(connection, key, true);
            JdbcKeyedStateStore.require(
                row.pins().equals(due.pinsDigest()) &&
                    row.snapshot().revision() == due.scheduled().stateRevision(),
                "SESSION_TIMER_CHANGED"
            );
            long through = JdbcKeyedTimers.freezeSessionCut(
                connection,
                due,
                generation,
                head,
                JdbcKeyedStateStore.now(connection)
            );
            return openingCheckpoint(connection, deployment, generation, initial) >= through;
        });
    }

    static long openingCheckpoint(
        Connection connection,
        JdbcRuleDeployments.Deployment deployment,
        UUID generation,
        long initial
    ) throws SQLException {
        try (
            var query = JdbcKeyedStateStore.prepare(
                connection,
                """
                SELECT input_generation,checkpoint FROM vcl_journal_window_cursor WHERE account_id=? AND deployment_epoch=?
                """
            )
        ) {
            query.setObject(1, deployment.account());
            query.setObject(2, deployment.epoch());
            try (var rows = query.executeQuery()) {
                if (!rows.next()) return initial;
                JdbcKeyedStateStore.require(
                    generation.equals(rows.getObject(1, UUID.class)) && rows.getLong(2) >= initial,
                    "WINDOW_INPUT_GENERATION_CHANGED"
                );
                return rows.getLong(2);
            }
        }
    }

    boolean advanceOpeningCheckpoint(
        JdbcRuleDeployments.Deployment deployment,
        UUID generation,
        long initial,
        long expected,
        long next,
        Long referenceRevision
    ) {
        JdbcKeyedStateStore.require(
            next > expected && next - expected == 1,
            "WINDOW_INPUT_CHECKPOINT_CHANGED"
        );
        return ownerContext.transaction(connection -> {
            JdbcRuleDeployments.lockExecutable(connection, deployment, true);
            if (
                referenceRevision != null &&
                referenceRevision != referenceCorrectionRevision(connection, deployment)
            ) return false;
            if (
                openingCheckpoint(connection, deployment, generation, initial) != expected
            ) return false;
            try (
                var update = JdbcKeyedStateStore.prepare(
                    connection,
                    """
                    UPDATE vcl_journal_window_cursor SET checkpoint=? WHERE account_id=? AND deployment_epoch=?
                    """
                )
            ) {
                update.setLong(1, next);
                update.setObject(2, deployment.account());
                update.setObject(3, deployment.epoch());
                if (update.executeUpdate() == 1) return true;
            }
            try (
                var insert = JdbcKeyedStateStore.prepare(
                    connection,
                    """
                    INSERT INTO vcl_journal_window_cursor(account_id,deployment_epoch,input_generation,checkpoint)
                    VALUES(?,?,?,?)
                    """
                )
            ) {
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
        try (var connection = ownerContext.connection()) {
            return referenceCorrectionRevision(connection, deployment);
        } catch (SQLException error) {
            throw JdbcKeyedStateStore.unavailable();
        }
    }

    static long referenceCorrectionRevision(
        Connection connection,
        JdbcRuleDeployments.Deployment deployment
    ) throws SQLException {
        try (
            var query = JdbcKeyedStateStore.prepare(
                connection,
                """
                SELECT reference_correction_revision FROM vcl_rule_deployments
                WHERE account_id=? AND deployment_epoch=?
                """
            )
        ) {
            query.setObject(1, deployment.account());
            query.setObject(2, deployment.epoch());
            try (var rows = query.executeQuery()) {
                JdbcKeyedStateStore.require(rows.next(), "DEPLOYMENT_UNAVAILABLE");
                return rows.getLong(1);
            }
        }
    }
}
