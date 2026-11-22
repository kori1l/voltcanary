package com.voltcanary.analytics.vcl.adapter;

import static com.voltcanary.analytics.vcl.adapter.JdbcKeyedStateStore.MAX_INBOX;

import com.voltcanary.analytics.vcl.KeyedStateStore.Acknowledgement;
import com.voltcanary.analytics.vcl.KeyedStateStore.Key;
import com.voltcanary.analytics.vcl.KeyedStateStore.Lease;
import com.voltcanary.analytics.vcl.KeyedStateStore.OutputKind;
import com.voltcanary.analytics.vcl.KeyedStateStore.Receipt;
import com.voltcanary.analytics.vcl.KeyedStateStore.Transition;
import java.time.Instant;
import java.util.List;

/** Focused transitions operations sharing the original state-store transaction boundary. */
final class JdbcKeyedTransitions {

    private final JdbcKeyedStateStore store;

    JdbcKeyedTransitions(JdbcKeyedStateStore store) {
        this.store = store;
    }

    public Receipt commit(Key key, Lease lease, Transition transition) {
        return commit(key, lease, transition, new KeyedCommit.Isolated());
    }

    Receipt commitJournalWindow(
        Key key,
        Lease lease,
        Transition transition,
        Instant openedAt,
        JdbcRuleDeployments.Deployment deployment,
        JdbcJournalWatermarks.Candidate watermark
    ) {
        return commit(
            key,
            lease,
            transition,
            new KeyedCommit.JournalWindow(
                java.util.Optional.ofNullable(deployment),
                openedAt,
                java.util.Optional.ofNullable(watermark)
            )
        );
    }

    Receipt commitSession(
        Key key,
        Lease lease,
        Transition transition,
        Instant openedAt,
        JdbcRuleDeployments.Deployment deployment
    ) {
        return commit(key, lease, transition, new KeyedCommit.Session(deployment, openedAt));
    }

    Receipt commitReviewedInput(
        Key key,
        Lease lease,
        Transition transition,
        Instant openedAt,
        JdbcRuleDeployments.Deployment deployment
    ) {
        return commit(key, lease, transition, new KeyedCommit.ReviewedInput(deployment, openedAt));
    }

    Receipt commitSustainedFinalization(
        Key key,
        Lease lease,
        Transition transition,
        Instant openedAt,
        JdbcRuleDeployments.Deployment deployment,
        Acknowledgement measure
    ) {
        return commit(
            key,
            lease,
            transition,
            new KeyedCommit.SustainedFinalization(deployment, openedAt, measure)
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
        return commit(
            key,
            lease,
            transition,
            new KeyedCommit.CoveredAggregate(
                deployment,
                java.util.Optional.ofNullable(openedAt),
                watermark
            )
        );
    }

    private Receipt commit(Key key, Lease lease, Transition transition, KeyedCommit context) {
        var deployment = context.deployment().orElse(null);
        var openedAt = context.opening().orElse(null);
        var watermark = context.watermark().orElse(null);
        var measure = context.acknowledgement().orElse(null);
        boolean session = context.requiresSessionFence();
        boolean sessionOccurrence = context instanceof KeyedCommit.Session;
        JdbcKeyedStateStore.require(key.digest().equals(lease.keyDigest()), "KEY_LEASE_CHANGED");
        if (deployment != null) {
            JdbcKeyedStateStore.require(
                key.accountId().equals(deployment.account()) &&
                    key.ruleId().equals(deployment.rule()) &&
                    key.deploymentEpoch().equals(deployment.epoch()),
                "WORKER_TARGET_INVALID"
            );
        }
        return store.transaction(connection -> {
            if (deployment != null) JdbcRuleDeployments.lockExecutable(
                connection,
                deployment,
                transition.consumedTimer() != null ||
                    measure != null ||
                    (sessionOccurrence && transition.expectedRevision() > 0)
            );
            if (session) {
                boolean opening = transition.expectedRevision() == 0;
                JdbcRuleDeployments.fenceSession(connection, deployment, openedAt, opening);
                if (opening && sessionOccurrence) {
                    JdbcKeyedStateStore.require(
                        !JdbcKeyedStateStore.drainingOccurrence(connection, deployment, key),
                        "SESSION_OCCURRENCE_DRAINING"
                    );
                }
            }
            boolean deployed =
                !session &&
                openedAt != null &&
                JdbcRuleDeployments.fenceJournalWindow(
                    connection,
                    key.accountId(),
                    key.ruleId(),
                    key.deploymentEpoch(),
                    openedAt,
                    transition.consumedTimer() != null,
                    watermark != null && watermark.finalizing() != null
                );
            var row = store.load(connection, key, true);
            JdbcKeyedStateStore.require(row.pins().equals(lease.pinsDigest()), "KEY_PINS_CHANGED");
            String inputKind = transition.consumedTimer() == null ? "OBSERVATION" : "TIMER";
            var existing = JdbcKeyedInbox.find(
                connection,
                key,
                row.snapshot().revision(),
                inputKind,
                transition.inputId(),
                transition.inputRevision()
            );
            if (existing.isPresent()) {
                var receipt = existing.get();
                JdbcKeyedStateStore.require(
                    receipt.transitionDigest().equals(transition.digest()),
                    "KEY_INPUT_CONFLICT"
                );
                JdbcKeyedStateStore.require(
                    receipt.committedRevision() == transition.expectedRevision() + 1,
                    "KEY_RECEIPT_CORRUPT"
                );
                return new Receipt(receipt.committedRevision(), true);
            }
            Instant now = JdbcKeyedStateStore.now(connection);
            JdbcKeyedStateStore.requireLease(row, lease, now);
            JdbcKeyedStateStore.require(
                row.snapshot().revision() == transition.expectedRevision(),
                "KEY_STATE_CONFLICT"
            );
            JdbcKeyedStateStore.require(row.inboxCount() < MAX_INBOX, "KEY_INBOX_LIMIT");
            if (transition.consumedTimer() != null) {
                var cut = JdbcKeyedTimers.requireDue(
                    connection,
                    key,
                    transition,
                    now,
                    sessionOccurrence
                );
                if (cut != null) {
                    JdbcKeyedStateStore.require(
                        JdbcKeyedStateStore.openingCheckpoint(
                            connection,
                            deployment,
                            cut.generation(),
                            0
                        ) >= cut.checkpoint(),
                        "SESSION_INPUT_PENDING"
                    );
                }
            }
            long revision = JdbcKeyedStateStore.apply(connection, key, row, transition);
            if (
                openedAt != null &&
                transition.consumedTimer() == null &&
                (!session || transition.expectedRevision() == 0)
            ) {
                JdbcKeyedStateStore.recordJournalOpening(connection, key, openedAt);
            }
            JdbcKeyedInbox.insert(connection, key, transition, revision, inputKind);
            if (measure != null) {
                JdbcKeyedStateStore.require(
                    JdbcKeyedOutbox.acknowledge(
                        connection,
                        key,
                        revision,
                        java.util.List.of(measure),
                        now,
                        OutputKind.MEASURE
                    ) == 1,
                    "SUSTAINED_MEASURE_CHANGED"
                );
            }
            if ((deployed || session) && watermark != null) {
                JdbcKeyedStateStore.require(
                    transition.consumedTimer() != null,
                    "WINDOW_WATERMARK_INVALID"
                );
                JdbcJournalWatermarks.advance(connection, key, watermark);
                JdbcJournalWatermarks.requireFinalizationFrontier(connection, key, watermark);
            }
            JdbcKeyedStateStore.requireLease(row, lease, JdbcKeyedStateStore.now(connection));
            return new Receipt(revision, false);
        });
    }
}
