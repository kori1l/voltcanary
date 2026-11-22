package com.voltcanary.analytics.vcl.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.voltcanary.analytics.vcl.KeyedStateStore;
import com.voltcanary.analytics.vcl.ScalarValue;
import com.voltcanary.analytics.vcl.SpatialFeatureSnapshot;
import com.voltcanary.analytics.vcl.VclDurationWitnessKernel;
import com.voltcanary.analytics.vcl.VclJournalInputPlan;
import com.voltcanary.analytics.vcl.VclSessionAbsenceKernel;
import com.voltcanary.analytics.vcl.VclTimingPlan;
import com.voltcanary.analytics.vcl.VclWindowPlan;
import com.voltcanary.analytics.vcl.VclWindowSessionKernel;
import com.voltcanary.analytics.vcl.adapter.RuleCompilationSnapshot.Metadata;
import com.voltcanary.analytics.vcl.adapter.RuleCompilationSnapshot.SessionAbsenceInput;
import com.voltcanary.analytics.vcl.adapter.RuleCompilationSnapshot.UnresolvedSessionInput;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclCompilation;
import com.voltcanary.vcl.VclCompiler;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclObservationPage;
import com.voltcanary.vcl.VclObservationReadiness;
import com.voltcanary.vcl.VclSourceCoverage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

final class RuleSessionPreparation {

    private final RuleCompilationSnapshot snapshot;

    RuleSessionPreparation(RuleCompilationSnapshot snapshot) {
        this.snapshot = snapshot;
    }

    static Optional<UnresolvedSessionInput> unresolvedSessionInput(
        JsonNode event,
        UUID subjectField,
        UUID occurrenceField,
        long maximumAgeUs,
        VclTimingPlan timing,
        VclJournalInputPlan input
    ) {
        UUID id = UUID.fromString(event.path("inputEventId").textValue());
        var at = Instant.parse(event.path("eventTime").textValue());
        var recorded = Instant.parse(event.path("recordedAt").textValue());
        if (
            timing.classify(at, recorded) == VclTimingPlan.Admission.QUARANTINED_FUTURE
        ) return Optional.empty();
        var subject = identity(event, subjectField, id);
        var occurrence = identity(event, occurrenceField, id);
        if (
            (subject != null && occurrence != null) ||
            input.select(observationFields(event, id)) == VclJournalInputPlan.Selection.EXCLUDED
        ) return Optional.empty();
        return Optional.of(
            new UnresolvedSessionInput(subject, occurrence, at, maximumAgeUs, timing)
        );
    }

    RuleSessionBatch prepareWindowSession(VclCompiler compiler, String nodeId) {
        var compilation = snapshot.compile(compiler);
        var node = compilation
            .graph()
            .stream()
            .filter(
                candidate ->
                    nodeId.equals(candidate.id()) && "window_session".equals(candidate.method())
            )
            .findFirst()
            .orElseThrow(() -> new VclException("SESSION_OPERATOR_REQUIRED", ""));
        var input = VclJournalInputPlan.compile(compilation, node, snapshot.new Metadata());
        var source = input.source();
        var target = new SourceBindingSnapshots.Target(
            UUID.fromString(source.config().path("bindingId").textValue()),
            source.config().path("schemaRevision").longValue()
        );
        var journal = snapshot.journals.get(target);
        if (journal == null) throw new VclException("SOURCE_CAPTURE_UNAVAILABLE", "");
        var plan = com.voltcanary.analytics.vcl.VclWindowSessionKernel.compile(
            compilation,
            nodeId,
            snapshot.new Metadata()
        );
        return new RuleSessionBatch(
            snapshot,
            plan,
            VclTimingPlan.compile(compilation),
            journal,
            input
        );
    }

    <T> List<T> sessionReplayRows(
        SourceCaptureSnapshot journal,
        JdbcRuleDeployments.Deployment deployment,
        Instant from,
        Instant through,
        java.util.function.Function<JsonNode, Optional<T>> route
    ) {
        var boundary = deployment.start().boundary();
        if (deployment.resumeFrom() != null && deployment.resumeFrom().isAfter(boundary)) boundary =
            deployment.resumeFrom();
        if (from.isBefore(boundary)) from = boundary;
        var until = through.plus(1, java.time.temporal.ChronoUnit.MICROS);
        var result = new ArrayList<T>();
        long remaining = RuleCompilationSnapshot.MAX_RETAINED_WINDOW_BYTES;
        while (from.isBefore(until)) {
            var end = from.plus(com.voltcanary.vcl.VclCoverageSearch.MAX_INTERVAL);
            if (end.isAfter(until)) end = until;
            String after = journal.generationStartCheckpoint();
            VclObservationPage page;
            do {
                page = journal.readPage(after, 128, from, end);
                remaining -= page.bytes().length;
                if (remaining < 0) throw new VclException("WINDOW_INPUT_BYTE_BUDGET_EXCEEDED", "");
                for (var event : page.observations()) {
                    route.apply(event).ifPresent(value -> {
                        if (result.size() >= VclDurationWitnessKernel.MAX_INPUTS) {
                            throw new VclException("SESSION_REPLAY_INPUT_LIMIT", "");
                        }
                        result.add(value);
                    });
                }
                after = page.nextCheckpoint();
            } while (page.hasMore());
            from = end;
        }
        snapshot.reauthorize();
        return List.copyOf(result);
    }

    RuleZoneBatch prepareZoneSession(VclCompiler compiler, String nodeId) {
        var compilation = snapshot.compile(compiler);
        var node = compilation
            .graph()
            .stream()
            .filter(
                candidate ->
                    nodeId.equals(candidate.id()) && "zone_session".equals(candidate.method())
            )
            .findFirst()
            .orElseThrow(() -> new VclException("SESSION_OPERATOR_REQUIRED", ""));
        var input = VclJournalInputPlan.compile(compilation, node, snapshot.new Metadata());
        var source = input.source();
        var target = new SourceBindingSnapshots.Target(
            UUID.fromString(source.config().path("bindingId").textValue()),
            source.config().path("schemaRevision").longValue()
        );
        var journal = snapshot.journals.get(target);
        if (journal == null) throw new VclException("SOURCE_CAPTURE_UNAVAILABLE", "");
        return new RuleZoneBatch(snapshot, compilation, node, journal, input);
    }

    VclSessionAbsenceKernel.Plan prepareSessionAbsence(
        VclCompiler compiler,
        String nodeId,
        VclDurationWitnessKernel.HoldContract hold,
        SpatialFeatureSnapshot geometry,
        UUID zone
    ) {
        return VclSessionAbsenceKernel.compile(
            snapshot.compile(compiler),
            nodeId,
            snapshot.new Metadata(),
            hold,
            geometry,
            zone
        );
    }

    List<UUID> retainedWindowReferences(JsonNode result, Instant windowStart, Instant now) {
        snapshot.reauthorize();
        if (
            !now.isBefore(
                windowStart.plus(
                    snapshot.policy.retentionDays(),
                    java.time.temporal.ChronoUnit.DAYS
                )
            )
        ) return List.of();
        var evidence = result.path("evidence");
        if (!evidence.isArray()) throw new VclException("WINDOW_MEASURE_CHANGED", "");
        var references = new java.util.LinkedHashSet<UUID>();
        try {
            for (var item : evidence) {
                references.add(UUID.fromString(item.textValue()));
                if (references.size() >= snapshot.policy.observationReferenceLimit()) break;
            }
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new VclException("WINDOW_MEASURE_CHANGED", "");
        }
        return List.copyOf(references);
    }

    String sessionEvaluatorPlanDigest(
        VclCompiler compiler,
        java.util.function.Supplier<List<SpatialFeatureSnapshot>> circles,
        String bundleDigest
    ) {
        var compilation = snapshot.compile(compiler);
        var methods = compilation
            .graph()
            .stream()
            .map(VclCompilation.Node::method)
            .filter(method -> !"filter".equals(method))
            .toList();
        boolean zone = methods.contains("zone_session");
        if (
            methods.size() != 5 ||
            !java.util.Set.copyOf(methods).equals(
                java.util.Set.of(
                    "read_stream",
                    zone ? "zone_session" : "window_session",
                    "observed_within",
                    "absent_within",
                    "emit_alert"
                )
            )
        ) {
            throw new VclException("EVALUATOR_PROFILE_UNAVAILABLE", "");
        }
        var absence = compilation
            .graph()
            .stream()
            .filter(node -> "absent_within".equals(node.method()))
            .findFirst()
            .orElseThrow();
        sessionAlertIntent(absence.id(), bundleDigest).orElseThrow(() ->
            new VclException("EVALUATOR_PROFILE_UNAVAILABLE", "")
        );
        var plans = new ArrayList<VclSessionAbsenceKernel.Plan>();
        var metadata = snapshot.new Metadata();
        if (zone) {
            var session = compilation
                .graph()
                .stream()
                .filter(node -> "zone_session".equals(node.method()))
                .findFirst()
                .orElseThrow();
            var input = VclJournalInputPlan.compile(compilation, session, metadata).source();
            var binding = metadata
                .binding(
                    UUID.fromString(input.config().path("bindingId").textValue()),
                    input.config().path("schemaRevision").longValue()
                )
                .orElseThrow(() -> new VclException("SESSION_SOURCE_CHANGED", ""));
            var routing = com.voltcanary.vcl.VclSourceBindingProfile.occurrenceFields(
                binding.definition()
            ).orElseThrow(() -> new VclException("SESSION_OCCURRENCE_ROUTING_UNAVAILABLE", ""));
            if (
                !routing
                    .subject()
                    .toString()
                    .equals(session.config().path("subjectFieldId").textValue()) ||
                !routing
                    .occurrence()
                    .toString()
                    .equals(session.config().path("occurrenceFieldId").textValue())
            ) {
                throw new VclException("SESSION_OCCURRENCE_ROUTING_MISMATCH", "");
            }
            for (var geometry : circles.get()) {
                for (UUID id : geometry.circles().keySet()) {
                    plans.add(
                        VclSessionAbsenceKernel.compile(
                            compilation,
                            absence.id(),
                            metadata,
                            null,
                            geometry,
                            id
                        )
                    );
                }
            }
        } else {
            plans.add(VclSessionAbsenceKernel.compile(compilation, absence.id(), metadata, null));
        }
        if (plans.isEmpty()) throw new VclException("EVALUATOR_PROFILE_UNAVAILABLE", "");
        var digests = plans
            .stream()
            .map(plan ->
                VclCanonical.sha256(
                    String.join(
                        "\n",
                        plan.sessionDigest(),
                        plan.timing().digest(),
                        plan.witness().digest()
                    )
                )
            )
            .sorted()
            .toList();
        snapshot.reauthorize();
        return VclCanonical.sha256(
            String.join(
                "\n",
                "DIRECT_JOURNAL_SESSION_EVALUATOR/1",
                compilation.semanticDigest(),
                compilation.accessScopeDigest(),
                String.join("\n", digests)
            )
        );
    }

    Optional<DurableJournalWindow.AlertIntent> sessionAlertIntent(
        String nodeId,
        String bundleDigest
    ) {
        for (var node : snapshot.document.spec().path("nodes")) {
            if (
                !"emit_alert".equals(node.path("method").textValue()) ||
                !nodeId.equals(node.at("/inputs/decisions").textValue())
            ) continue;
            var config = node.path("config");
            if (
                !"RULE_SUBJECT_OCCURRENCE".equals(config.path("dedup").textValue()) ||
                !snapshot.policyPin
                    .id()
                    .toString()
                    .equals(config.path("evidencePolicyId").textValue())
            ) {
                throw new VclException("SESSION_ALERT_POLICY_UNSUPPORTED", "");
            }
            return Optional.of(
                new DurableJournalWindow.AlertIntent(
                    bundleDigest,
                    snapshot.grantPin.id(),
                    snapshot.grantPin.revision(),
                    snapshot.grantPin.snapshotDigest(),
                    snapshot.policyPin.id(),
                    snapshot.policyPin.snapshotDigest(),
                    config.path("severity").textValue()
                )
            );
        }
        return Optional.empty();
    }

    SessionAbsenceInput sessionPreviewFacts(
        VclSessionAbsenceKernel.Plan plan,
        KeyedStateStore.Key key,
        VclWindowPlan.Window window,
        UUID proofId
    ) {
        var source = plan.source();
        var journal = snapshot.journals.get(
            new SourceBindingSnapshots.Target(source.id(), source.revision())
        );
        if (journal == null) throw new VclException("SOURCE_CAPTURE_UNAVAILABLE", "");
        var facts = readSessionAbsenceInput(
            plan,
            key,
            window.endExclusive().minus(1, java.time.temporal.ChronoUnit.MICROS),
            window.startInclusive(),
            window,
            journal.capturedHeadCheckpoint(),
            null,
            false
        );
        var proof = sessionPreviewProof(plan, window, proofId);
        return new SessionAbsenceInput(facts.cut(), proof, facts.lastRelevantCheckpoint());
    }

    VclSourceCoverage sessionPreviewProof(
        VclSessionAbsenceKernel.Plan plan,
        VclWindowPlan.Window window,
        UUID proofId
    ) {
        if (proofId == null) return null;
        var source = plan.source();
        var journal = snapshot.journals.get(
            new SourceBindingSnapshots.Target(source.id(), source.revision())
        );
        if (journal == null) throw new VclException("SOURCE_CAPTURE_UNAVAILABLE", "");
        return journal.coverage(
            proofId,
            window.startInclusive(),
            plan.timing().windowCloseDueAt(window.endExclusive())
        );
    }

    SessionAbsenceInput readSessionAbsenceInput(
        VclSessionAbsenceKernel.Plan plan,
        KeyedStateStore.Key key,
        Instant readEnd,
        Instant replayStart,
        VclWindowPlan.Window coverageWindow,
        String closingCheckpoint,
        UUID proofId,
        boolean speculative
    ) {
        snapshot.reauthorize();
        var source = plan.source();
        var journal = snapshot.journals.get(
            new SourceBindingSnapshots.Target(source.id(), source.revision())
        );
        if (journal == null || !journal.accountId().equals(key.accountId())) {
            throw new VclException("SOURCE_CAPTURE_UNAVAILABLE", "");
        }
        var closingCut = journal.atCheckpoint(closingCheckpoint);
        var proof =
            proofId == null
                ? null
                : closingCut.coverage(
                      proofId,
                      coverageWindow.startInclusive(),
                      plan.timing().windowCloseDueAt(coverageWindow.endExclusive()),
                      true
                  );
        var input = speculative
            ? closingCut.atCurrentHead()
            : proof != null && proof.qualifiesEventTime()
              ? closingCut.forFinalization(proof)
              : closingCut;
        // The journal range is half-open. Include the actual close observation at microsecond
        // precision.
        var until = readEnd.plus(1, java.time.temporal.ChronoUnit.MICROS);
        var observations = new ArrayList<VclSessionAbsenceKernel.Sample>();
        var eventIds = new HashSet<UUID>();
        UUID unresolved = null;
        UUID unresolvedFilter = null;
        UUID correctionRequired = null;
        long closedAtCheckpoint = VclObservationReadiness.checkpoint(closingCheckpoint);
        long lastRelevantCheckpoint = closedAtCheckpoint;
        long remainingBytes = RuleCompilationSnapshot.MAX_RETAINED_WINDOW_BYTES;
        for (var from = replayStart; from.isBefore(until); ) {
            var chunkEnd = from.plus(com.voltcanary.vcl.VclCoverageSearch.MAX_INTERVAL);
            if (chunkEnd.isAfter(until)) chunkEnd = until;
            String after = input.generationStartCheckpoint();
            VclObservationPage page;
            do {
                page = input.readPage(after, 128, from, chunkEnd);
                remainingBytes -= page.bytes().length;
                if (remainingBytes < 0) throw new VclException(
                    "WINDOW_INPUT_BYTE_BUDGET_EXCEEDED",
                    ""
                );
                for (var event : page.observations()) {
                    UUID id = UUID.fromString(event.path("inputEventId").textValue());
                    if (!eventIds.add(id)) throw new VclException(
                        "WINDOW_INPUT_EVENT_DUPLICATE",
                        ""
                    );
                    var subject = identity(event, plan.subjectField(), id);
                    var occurrence = identity(event, plan.occurrenceField(), id);
                    if (
                        (subject != null && !subject.equals(key.subjectDigest())) ||
                        (occurrence != null && !occurrence.equals(key.occurrenceDigest()))
                    ) continue;
                    var fields = observationFields(event, id);
                    var selection = plan.input().select(fields);
                    if (selection == VclJournalInputPlan.Selection.EXCLUDED) continue;
                    long checkpoint = VclObservationReadiness.checkpoint(
                        event.path("checkpoint").textValue()
                    );
                    lastRelevantCheckpoint = Math.max(lastRelevantCheckpoint, checkpoint);
                    if (checkpoint > closedAtCheckpoint) {
                        if (correctionRequired == null) correctionRequired = id;
                    }
                    var eventTime = Instant.parse(event.path("eventTime").textValue());
                    if (selection == VclJournalInputPlan.Selection.UNKNOWN) {
                        if (
                            unresolvedFilter == null &&
                            (!eventTime.isBefore(coverageWindow.startInclusive()) ||
                                checkpoint > closedAtCheckpoint)
                        ) unresolvedFilter = id;
                        continue;
                    }
                    if (subject == null || occurrence == null) {
                        if (
                            unresolved == null &&
                            (!eventTime.isBefore(coverageWindow.startInclusive()) ||
                                checkpoint > closedAtCheckpoint)
                        ) {
                            unresolved = id;
                        }
                        continue;
                    }
                    if (observations.size() >= VclDurationWitnessKernel.MAX_INPUTS) {
                        throw new VclException("WITNESS_INPUT_LIMIT", "");
                    }
                    observations.add(
                        new VclSessionAbsenceKernel.Sample(
                            key,
                            id,
                            eventTime,
                            Instant.parse(event.path("recordedAt").textValue()),
                            fields
                        )
                    );
                }
                after = page.nextCheckpoint();
            } while (page.hasMore());
            from = chunkEnd;
        }
        snapshot.reauthorize();
        return new SessionAbsenceInput(
            new VclSessionAbsenceKernel.InputCut(
                source.id(),
                source.revision(),
                input.snapshotId(),
                input.inputGeneration(),
                input.capturedHeadCheckpoint(),
                observations,
                unresolved,
                correctionRequired,
                unresolvedFilter
            ),
            proof,
            lastRelevantCheckpoint
        );
    }

    static Map<UUID, ScalarValue> observationFields(JsonNode event, UUID id) {
        var fields = new LinkedHashMap<UUID, ScalarValue>();
        event
            .path("fields")
            .forEach(field ->
                fields.put(
                    UUID.fromString(field.path("fieldId").textValue()),
                    RuleCompilationSnapshot.observationValue(field, id)
                )
            );
        return fields;
    }

    static String identity(JsonNode event, UUID field, UUID observation) {
        var value = RuleCompilationSnapshot.observationValue(
            RuleCompilationSnapshot.observationField(event, field.toString()),
            observation
        );
        return value.status() == ScalarValue.Status.OK && !value.isNull()
            ? VclWindowSessionKernel.identity(value)
            : null;
    }
}
