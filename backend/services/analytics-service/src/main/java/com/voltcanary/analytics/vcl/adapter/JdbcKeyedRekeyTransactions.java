package com.voltcanary.analytics.vcl.adapter;

import com.voltcanary.analytics.vcl.KeyedStateStore.Acknowledgement;
import com.voltcanary.analytics.vcl.KeyedStateStore.Key;
import com.voltcanary.analytics.vcl.KeyedStateStore.Lease;
import com.voltcanary.analytics.vcl.KeyedStateStore.OutputKind;
import com.voltcanary.analytics.vcl.KeyedStateStore.RekeyReceipt;
import com.voltcanary.analytics.vcl.KeyedStateStore.Transition;
import com.voltcanary.vcl.VclException;
import java.time.Instant;
import java.util.List;

/** Focused rekeyTransactions operations sharing the original state-store transaction boundary. */
final class JdbcKeyedRekeyTransactions {

    private final JdbcKeyedStateStore store;

    JdbcKeyedRekeyTransactions(JdbcKeyedStateStore store) {
        this.store = store;
    }

    public RekeyReceipt consumeRekey(
        Key sourceKey,
        Lease sourceLease,
        Key targetKey,
        Lease targetLease,
        Transition transition
    ) {
        return consumeRekey(sourceKey, sourceLease, targetKey, targetLease, transition, null, null);
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
        JdbcKeyedStateStore.require(
            deployment.account().equals(targetKey.accountId()) &&
                deployment.rule().equals(targetKey.ruleId()) &&
                deployment.epoch().equals(targetKey.deploymentEpoch()),
            "WORKER_TARGET_INVALID"
        );
        return consumeRekey(
            sourceKey,
            sourceLease,
            targetKey,
            targetLease,
            transition,
            deployment,
            openedAt
        );
    }

    private RekeyReceipt consumeRekey(
        Key sourceKey,
        Lease sourceLease,
        Key targetKey,
        Lease targetLease,
        Transition transition,
        JdbcRuleDeployments.Deployment deployment,
        Instant openedAt
    ) {
        JdbcKeyedStateStore.require(
            sourceKey.accountId().equals(targetKey.accountId()) &&
                sourceKey.ruleId().equals(targetKey.ruleId()) &&
                sourceKey.deploymentEpoch().equals(targetKey.deploymentEpoch()),
            "KEY_OUTPUT_TARGET_CHANGED"
        );
        JdbcKeyedStateStore.require(
            sourceKey.digest().equals(sourceLease.keyDigest()) &&
                targetKey.digest().equals(targetLease.keyDigest()),
            "KEY_LEASE_CHANGED"
        );
        JdbcKeyedStateStore.require(
            sourceLease.pinsDigest().equals(targetLease.pinsDigest()),
            "KEY_PINS_CHANGED"
        );
        JdbcKeyedStateStore.require(transition.consumedTimer() == null, "KEY_REKEY_INPUT_CHANGED");
        boolean sameKey = sourceKey.digest().equals(targetKey.digest());
        JdbcKeyedStateStore.require(
            !sameKey || sourceKey.equals(targetKey),
            "KEY_IDENTITY_CHANGED"
        );
        return store.transaction(connection -> {
            if (deployment != null) {
                JdbcRuleDeployments.lockExecutable(connection, deployment, false);
                JdbcRuleDeployments.fenceSession(connection, deployment, openedAt, true);
            }
            // Both directions acquire SQL locks in the same order; RPCs/evaluation never
            // run under these locks.
            boolean sourceFirst = sourceKey.digest().compareTo(targetKey.digest()) <= 0;
            var first = store.load(connection, sourceFirst ? sourceKey : targetKey, true);
            var second = sameKey
                ? first
                : store.load(connection, sourceFirst ? targetKey : sourceKey, true);
            var origin = sourceFirst ? first : second;
            var target = sourceFirst ? second : first;
            JdbcKeyedStateStore.require(
                origin.pins().equals(sourceLease.pinsDigest()) &&
                    target.pins().equals(targetLease.pinsDigest()),
                "KEY_PINS_CHANGED"
            );
            var output = JdbcKeyedOutbox.find(
                connection,
                sourceKey,
                origin.snapshot().revision(),
                transition.inputId()
            ).orElseThrow(() -> new VclException("KEY_OUTPUT_UNAVAILABLE", ""));
            JdbcKeyedStateStore.require(
                output.output().kind() == OutputKind.REKEY &&
                    targetKey.equals(output.output().target()),
                "KEY_OUTPUT_TARGET_CHANGED"
            );
            JdbcKeyedStateStore.require(
                transition.inputRevision() == output.stateRevision() &&
                    transition.inputDigest().equals(output.output().rekeyInputDigest(sourceKey)),
                "KEY_REKEY_INPUT_CHANGED"
            );
            var existing = JdbcKeyedRekeys.find(
                connection,
                sourceKey,
                targetKey,
                output,
                transition,
                target.snapshot().revision(),
                origin.pins()
            );
            if (existing.isPresent()) return existing.get();
            JdbcKeyedStateStore.require(output.receiptId() == null, "KEY_OUTPUT_ACK_CONFLICT");
            Instant now = JdbcKeyedStateStore.now(connection);
            JdbcKeyedStateStore.requireLease(origin, sourceLease, now);
            JdbcKeyedStateStore.requireLease(target, targetLease, now);
            JdbcKeyedStateStore.apply(connection, targetKey, target, transition);
            if (deployment != null && transition.expectedRevision() == 0) {
                JdbcKeyedStateStore.recordJournalOpening(connection, targetKey, openedAt);
            }
            var receipt = JdbcKeyedRekeys.insert(
                connection,
                sourceKey,
                targetKey,
                output,
                transition,
                origin.pins()
            );
            JdbcKeyedOutbox.acknowledge(
                connection,
                sourceKey,
                origin.snapshot().revision(),
                java.util.List.of(
                    new Acknowledgement(
                        output.output().id(),
                        output.output().digest(),
                        receipt.id(),
                        receipt.digest()
                    )
                ),
                now,
                OutputKind.REKEY
            );
            Instant finishedAt = JdbcKeyedStateStore.now(connection);
            JdbcKeyedStateStore.requireLease(origin, sourceLease, finishedAt);
            JdbcKeyedStateStore.requireLease(target, targetLease, finishedAt);
            return receipt;
        });
    }
}
