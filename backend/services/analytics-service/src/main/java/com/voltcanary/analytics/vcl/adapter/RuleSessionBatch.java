package com.voltcanary.analytics.vcl.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.voltcanary.analytics.vcl.KeyedStateStore;
import com.voltcanary.analytics.vcl.ScalarValue;
import com.voltcanary.analytics.vcl.VclJournalInputPlan;
import com.voltcanary.analytics.vcl.VclTimingPlan;
import com.voltcanary.analytics.vcl.VclWindowSessionKernel;
import com.voltcanary.analytics.vcl.adapter.RuleCompilationSnapshot.SessionObservation;
import com.voltcanary.analytics.vcl.adapter.RuleCompilationSnapshot.UnresolvedSessionInput;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

final class RuleSessionBatch {
    private final RuleCompilationSnapshot snapshot;
    final com.voltcanary.analytics.vcl.VclWindowSessionKernel.Plan plan;
    final VclTimingPlan timing;
    final SourceCaptureSnapshot journal;
    final VclJournalInputPlan input;

    RuleSessionBatch(
            RuleCompilationSnapshot snapshot,
            com.voltcanary.analytics.vcl.VclWindowSessionKernel.Plan plan,
            VclTimingPlan timing,
            SourceCaptureSnapshot journal,
            VclJournalInputPlan input) {
        this.snapshot = snapshot;
        this.plan = plan;
        this.timing = timing;
        this.journal = journal;
        this.input = input;
    }

    Optional<SessionObservation<VclWindowSessionKernel.Observation>> route(
            UUID epoch, JsonNode event) {
        var at = Instant.parse(event.path("eventTime").textValue());
        var recorded = Instant.parse(event.path("recordedAt").textValue());
        if (timing.classify(at, recorded) == VclTimingPlan.Admission.QUARANTINED_FUTURE)
            return Optional.empty();
        UUID id = UUID.fromString(event.path("inputEventId").textValue());
        var fields = RuleCompilationSnapshot.observationFields(event, id);
        var subject = fields.get(plan.subjectField());
        var occurrence = fields.get(plan.occurrenceField());
        if (subject == null
                || occurrence == null
                || subject.status() != ScalarValue.Status.OK
                || occurrence.status() != ScalarValue.Status.OK
                || subject.isNull()
                || occurrence.isNull()) return Optional.empty();
        var selection = input.select(fields);
        if (selection == VclJournalInputPlan.Selection.EXCLUDED) return Optional.empty();
        var key =
                new com.voltcanary.analytics.vcl.KeyedStateStore.Key(
                        journal.accountId(),
                        plan.rule(),
                        epoch,
                        com.voltcanary.analytics.vcl.VclWindowSessionKernel.identity(subject),
                        com.voltcanary.analytics.vcl.VclWindowSessionKernel.identity(occurrence));
        return Optional.of(
                new SessionObservation<>(
                        key,
                        new VclWindowSessionKernel.Observation(id, at, fields),
                        selection == VclJournalInputPlan.Selection.INCLUDED));
    }

    Optional<UnresolvedSessionInput> unresolved(JsonNode event) {
        return RuleCompilationSnapshot.unresolvedSessionInput(
                event,
                plan.subjectField(),
                plan.occurrenceField(),
                plan.maxDurationUs(),
                timing,
                input);
    }

    List<VclWindowSessionKernel.Observation> replayHistory(
            JdbcRuleDeployments.Deployment deployment,
            KeyedStateStore.Key key,
            String checkpoint,
            Instant from,
            Instant through) {
        return snapshot.sessionReplayRows(
                journal.atCheckpoint(checkpoint),
                deployment,
                from,
                through,
                event ->
                        route(deployment.epoch(), event)
                                .filter(value -> value.included() && key.equals(value.key()))
                                .map(SessionObservation::value));
    }

    com.voltcanary.analytics.vcl.DurableWindowSession durable(
            JdbcKeyedStateStore store,
            JdbcRuleDeployments.Deployment deployment,
            com.voltcanary.analytics.vcl.KeyedStateStore.Key key,
            Runnable deploymentCheck) {
        return durable(store, deployment, key, deploymentCheck, null, null, null);
    }

    com.voltcanary.analytics.vcl.DurableWindowSession durable(
            JdbcKeyedStateStore store,
            JdbcRuleDeployments.Deployment deployment,
            KeyedStateStore.Key key,
            Runnable deploymentCheck,
            String absence,
            String checkpoint,
            Instant observedAt) {
        return new com.voltcanary.analytics.vcl.DurableWindowSession(
                store,
                plan,
                key,
                deployment.bundleDigest(),
                () -> {
                    snapshot.reauthorize();
                    deploymentCheck.run();
                },
                (target, lease, transition, openedAt) ->
                        store.commitSession(
                                target,
                                lease,
                                SessionAbsenceSchedule.schedule(
                                        store,
                                        target,
                                        lease,
                                        transition,
                                        absence,
                                        checkpoint,
                                        openedAt,
                                        observedAt,
                                        false,
                                        timing),
                                openedAt,
                                deployment),
                timing::windowCloseDueAt);
    }
}
