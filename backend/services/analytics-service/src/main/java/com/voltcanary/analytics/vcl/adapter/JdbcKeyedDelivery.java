package com.voltcanary.analytics.vcl.adapter;

import static com.voltcanary.analytics.vcl.adapter.JdbcKeyedStateStore.ALERT_PARSER;

import com.voltcanary.analytics.vcl.KeyedStateStore.Acknowledgement;
import com.voltcanary.analytics.vcl.KeyedStateStore.Key;
import com.voltcanary.analytics.vcl.KeyedStateStore.Lease;
import com.voltcanary.analytics.vcl.KeyedStateStore.Output;
import com.voltcanary.analytics.vcl.KeyedStateStore.OutputKind;
import com.voltcanary.analytics.vcl.KeyedStateStore.OutputStatus;
import com.voltcanary.analytics.vcl.KeyedStateStore.State;
import com.voltcanary.analytics.vcl.adapter.JdbcKeyedStateStore.PendingAlert;
import com.voltcanary.vcl.VclException;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Focused delivery operations sharing the original state-store transaction boundary. */
final class JdbcKeyedDelivery {

    private final JdbcKeyedStateStore store;

    JdbcKeyedDelivery(JdbcKeyedStateStore store) {
        this.store = store;
    }

    public java.util.List<OutputStatus> pendingOutputs(Key key, String pinsDigest, int limit) {
        if (limit < 1 || limit > 128) throw new IllegalArgumentException(
            "Invalid outbox read limit"
        );
        return store.transaction(connection -> {
            var row = store.load(connection, key, true);
            JdbcKeyedStateStore.require(row.pins().equals(pinsDigest), "KEY_PINS_CHANGED");
            return JdbcKeyedOutbox.pending(connection, key, row.snapshot().revision(), limit);
        });
    }

    public OutputStatus inspectOutput(Key key, String pinsDigest, UUID outputId) {
        java.util.Objects.requireNonNull(outputId);
        return store.transaction(connection -> {
            var row = store.load(connection, key, true);
            JdbcKeyedStateStore.require(row.pins().equals(pinsDigest), "KEY_PINS_CHANGED");
            return JdbcKeyedOutbox.find(
                connection,
                key,
                row.snapshot().revision(),
                outputId
            ).orElseThrow(() -> new VclException("KEY_OUTPUT_UNAVAILABLE", ""));
        });
    }

    public int acknowledge(Key key, Lease lease, java.util.List<Acknowledgement> acknowledgements) {
        JdbcKeyedStateStore.require(key.digest().equals(lease.keyDigest()), "KEY_LEASE_CHANGED");
        if (
            acknowledgements == null || acknowledgements.size() > 128
        ) throw new IllegalArgumentException("Invalid acknowledgement budget");
        var snapshot = java.util.List.copyOf(acknowledgements);
        var ids = new java.util.HashSet<UUID>();
        for (var ack : snapshot)
            if (!ids.add(ack.outputId())) throw new IllegalArgumentException(
                "Duplicate acknowledgement"
            );
        return store.transaction(connection -> {
            var row = store.load(connection, key, true);
            Instant now = JdbcKeyedStateStore.now(connection);
            JdbcKeyedStateStore.requireLease(row, lease, now);
            int updated = JdbcKeyedOutbox.acknowledge(
                connection,
                key,
                row.snapshot().revision(),
                snapshot,
                now,
                null
            );
            JdbcKeyedStateStore.requireLease(row, lease, JdbcKeyedStateStore.now(connection));
            return updated;
        });
    }

    java.util.List<PendingAlert> pendingAlerts(int limit, int offset) {
        if (
            limit < 1 || limit > 128 || offset < 0 || offset > 1_000_000
        ) throw new IllegalArgumentException("Invalid alert page");
        try (
            var connection = store.connection();
            var query = JdbcKeyedStateStore.prepare(
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
                """
            )
        ) {
            query.setInt(1, limit);
            query.setInt(2, offset);
            try (var rows = query.executeQuery()) {
                var result = new java.util.ArrayList<PendingAlert>();
                while (rows.next()) {
                    var key = new Key(
                        rows.getObject(1, UUID.class),
                        rows.getObject(2, UUID.class),
                        rows.getObject(3, UUID.class),
                        rows.getString(4),
                        rows.getString(5)
                    );
                    JdbcKeyedStateStore.require(
                        key.digest().equals(rows.getString(6)),
                        "KEY_IDENTITY_CHANGED"
                    );
                    result.add(new PendingAlert(key, rows.getObject(7, UUID.class)));
                }
                return java.util.List.copyOf(result);
            }
        } catch (SQLException error) {
            throw JdbcKeyedStateStore.unavailable();
        }
    }

    void deliverAlert(
        PendingAlert pending,
        JdbcRuleDeployments.Deployment deployment,
        java.util.function.BiFunction<Output, Output, Acknowledgement> send
    ) {
        var key = pending.key();
        JdbcKeyedStateStore.require(
            key.accountId().equals(deployment.account()) &&
                key.ruleId().equals(deployment.rule()) &&
                key.deploymentEpoch().equals(deployment.epoch()) &&
                deployment.start() != null,
            "WORKER_TARGET_INVALID"
        );
        store.transaction(connection -> {
            if (
                deployment.state() == JdbcRuleDeployments.State.REVOKED ||
                deployment.state() == JdbcRuleDeployments.State.PAUSED
            ) {
                JdbcRuleDeployments.lockCurrent(connection, deployment);
            } else {
                JdbcRuleDeployments.lockExecutable(connection, deployment, true);
            }
            var row = store.load(connection, key, true);
            long revision = row.snapshot().revision();
            var saved = JdbcKeyedOutbox.find(
                connection,
                key,
                revision,
                pending.outputId()
            ).orElseThrow(() -> new VclException("KEY_OUTPUT_UNAVAILABLE", ""));
            JdbcKeyedStateStore.require(
                saved.output().kind() == OutputKind.ALERT_PROPOSAL,
                "KEY_OUTPUT_CHANGED"
            );
            if (saved.receiptId() != null) return null;
            try (
                var prior = JdbcKeyedStateStore.prepare(
                    connection,
                    """
                    SELECT 1 FROM vcl_keyed_outbox WHERE account_id=? AND key_digest=?
                        AND output_kind='ALERT_PROPOSAL' AND acknowledged_at IS NULL AND state_revision<?
                    FETCH FIRST 1 ROW ONLY
                    """
                )
            ) {
                prior.setObject(1, key.accountId());
                prior.setString(2, key.digest());
                prior.setLong(3, saved.stateRevision());
                try (var pendingPrior = prior.executeQuery()) {
                    JdbcKeyedStateStore.require(
                        !pendingPrior.next(),
                        "ALERT_PROPOSAL_OUT_OF_ORDER"
                    );
                }
            }
            var payload = ALERT_PARSER.readJson(
                saved.output().payload().payload().getBytes(java.nio.charset.StandardCharsets.UTF_8)
            );
            UUID measureId = UUID.fromString(payload.path("measure").textValue());
            var measure = JdbcKeyedOutbox.find(connection, key, revision, measureId).orElseThrow(
                () -> new VclException("KEY_OUTPUT_UNAVAILABLE", "")
            );
            JdbcKeyedStateStore.require(
                measure.stateRevision() == saved.stateRevision() &&
                    measure.output().kind() == OutputKind.MEASURE &&
                    measure
                        .output()
                        .payload()
                        .digest()
                        .equals(payload.path("measureDigest").textValue()),
                "KEY_OUTPUT_CHANGED"
            );
            var acknowledgement = send.apply(saved.output(), measure.output());
            JdbcKeyedStateStore.require(
                saved.output().id().equals(acknowledgement.outputId()) &&
                    saved.output().digest().equals(acknowledgement.outputDigest()),
                "KEY_OUTPUT_CHANGED"
            );
            JdbcKeyedStateStore.require(
                JdbcKeyedOutbox.acknowledge(
                    connection,
                    key,
                    revision,
                    java.util.List.of(acknowledgement),
                    JdbcKeyedStateStore.now(connection),
                    OutputKind.ALERT_PROPOSAL
                ) == 1,
                "KEY_OUTPUT_ACK_CONFLICT"
            );
            return null;
        });
    }
}
