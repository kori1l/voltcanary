package com.voltcanary.analytics.vcl.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.voltcanary.analytics.vcl.KeyedStateStore;
import com.voltcanary.analytics.vcl.ScalarValue;
import com.voltcanary.analytics.vcl.VclJournalInputPlan;
import com.voltcanary.analytics.vcl.VclTimingPlan;
import com.voltcanary.analytics.vcl.VclZoneSessionKernel;
import com.voltcanary.analytics.vcl.adapter.RuleCompilationSnapshot.SessionObservation;
import com.voltcanary.analytics.vcl.adapter.RuleCompilationSnapshot.UnresolvedSessionInput;
import com.voltcanary.vcl.VclCompilation;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

final class RuleZoneBatch {
    private final RuleCompilationSnapshot snapshot;
    final VclCompilation compilation;
    final VclCompilation.Node node;
    final VclTimingPlan timing;
    final SourceCaptureSnapshot journal;
    final VclJournalInputPlan input;

    RuleZoneBatch(
            RuleCompilationSnapshot snapshot,
            VclCompilation compilation,
            VclCompilation.Node node,
            SourceCaptureSnapshot journal,
            VclJournalInputPlan input) {
        this.snapshot = snapshot;
        this.compilation = compilation;
        this.node = node;
        this.journal = journal;
        this.input = input;
        timing = VclTimingPlan.compile(compilation);
    }

    Optional<SessionObservation<VclZoneSessionKernel.Observation>> route(
            UUID epoch, JsonNode event) {
        var at = Instant.parse(event.path("eventTime").textValue());
        var recorded = Instant.parse(event.path("recordedAt").textValue());
        if (timing.classify(at, recorded) == VclTimingPlan.Admission.QUARANTINED_FUTURE)
            return Optional.empty();
        UUID id = UUID.fromString(event.path("inputEventId").textValue());
        var config = node.config();
        var subject =
                RuleCompilationSnapshot.observationValue(
                        RuleCompilationSnapshot.observationField(
                                event, config.path("subjectFieldId").textValue()),
                        id);
        var occurrence =
                RuleCompilationSnapshot.observationValue(
                        RuleCompilationSnapshot.observationField(
                                event, config.path("occurrenceFieldId").textValue()),
                        id);
        if (subject.status() != ScalarValue.Status.OK
                || occurrence.status() != ScalarValue.Status.OK
                || subject.isNull()
                || occurrence.isNull()) return Optional.empty();
        var selection = input.select(RuleCompilationSnapshot.observationFields(event, id));
        if (selection == VclJournalInputPlan.Selection.EXCLUDED) return Optional.empty();
        var position =
                RuleCompilationSnapshot.observationValue(
                        RuleCompilationSnapshot.observationField(
                                event, config.path("positionFieldId").textValue()),
                        id);
        var zone =
                RuleCompilationSnapshot.observationValue(
                        RuleCompilationSnapshot.observationField(
                                event, config.path("zoneFieldId").textValue()),
                        id);
        var accuracyId = config.path("accuracyFieldId").textValue();
        var accuracy =
                accuracyId == null
                        ? null
                        : RuleCompilationSnapshot.observationValue(
                                RuleCompilationSnapshot.observationField(event, accuracyId), id);
        var key =
                new com.voltcanary.analytics.vcl.KeyedStateStore.Key(
                        journal.accountId(),
                        UUID.fromString(snapshot.document.spec().path("ruleId").textValue()),
                        epoch,
                        com.voltcanary.analytics.vcl.VclWindowSessionKernel.identity(subject),
                        com.voltcanary.analytics.vcl.VclWindowSessionKernel.identity(occurrence));
        return Optional.of(
                new SessionObservation<>(
                        key,
                        VclZoneSessionKernel.observation(id, at, key, zone, position, accuracy),
                        selection == VclJournalInputPlan.Selection.INCLUDED));
    }

    Optional<UnresolvedSessionInput> unresolved(JsonNode event) {
        var config = node.config();
        return RuleCompilationSnapshot.unresolvedSessionInput(
                event,
                UUID.fromString(config.path("subjectFieldId").textValue()),
                UUID.fromString(config.path("occurrenceFieldId").textValue()),
                Math.multiplyExact(2, Long.parseLong(config.path("maxDurationUs").textValue())),
                timing,
                input);
    }

    List<VclZoneSessionKernel.Observation> replayHistory(
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

    com.voltcanary.analytics.vcl.DurableZoneSession durable(
            JdbcKeyedStateStore store,
            JdbcRuleDeployments.Deployment deployment,
            com.voltcanary.analytics.vcl.KeyedStateStore.Key key,
            UUID zone,
            long revision,
            WorkerCircleReads circles,
            Runnable deploymentCheck) {
        return durable(
                store, deployment, key, zone, revision, circles, deploymentCheck, null, null, null);
    }

    com.voltcanary.analytics.vcl.DurableZoneSession durable(
            JdbcKeyedStateStore store,
            JdbcRuleDeployments.Deployment deployment,
            KeyedStateStore.Key key,
            UUID zone,
            long revision,
            WorkerCircleReads circles,
            Runnable deploymentCheck,
            String absence,
            String checkpoint,
            Instant observedAt) {
        String scope = compilation.accessScopeDigest();
        var geometry = circles.read(deployment, zone, revision, scope);
        var plan =
                com.voltcanary.analytics.vcl.VclZoneSessionKernel.compile(
                        compilation, node.id(), geometry, zone);
        return new com.voltcanary.analytics.vcl.DurableZoneSession(
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
                                        true,
                                        timing),
                                openedAt,
                                deployment),
                timing::windowCloseDueAt,
                () -> circles.current(deployment, zone, plan.circle().revision(), scope));
    }
}
