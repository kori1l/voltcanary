package com.voltcanary.analytics.vcl;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.voltcanary.vcl.VclCompilation;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclMetadata;
import com.voltcanary.vcl.VclObservationReadiness;
import com.voltcanary.vcl.VclParser;
import com.voltcanary.vcl.VclRegistry;
import com.voltcanary.vcl.VclScalarCompiler;
import com.voltcanary.vcl.VclScalarProgram;
import com.voltcanary.vcl.VclSourceBindingProfile;
import com.voltcanary.vcl.VclSourceCoverage;
import com.voltcanary.vcl.VclType;
import com.voltcanary.vcl.VclUnits;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Typed observed_within/absent_within composition for one exact direct-source session. */
public final class VclSessionAbsenceKernel {

    private static final VclParser PARSER = new VclParser();
    private static final VclRegistry REGISTRY = new VclRegistry(PARSER);
    private static final VclUnits UNITS = VclUnits.standard();
    private final VclScalarEvaluator evaluator = new VclScalarEvaluator(UNITS, REGISTRY.digest());

    public record Sample(
        KeyedStateStore.Key key,
        UUID id,
        Instant at,
        Instant recordedAt,
        Map<UUID, ScalarValue> fields
    ) {
        public Sample {
            fields = Map.copyOf(fields);
        }
    }

    /** The adapter must supply the complete matching batch at this immutable owner cut. */
    public record InputCut(
        UUID binding,
        long schemaRevision,
        UUID snapshot,
        UUID generation,
        String throughCheckpoint,
        List<Sample> observations,
        UUID unresolvedIdentity,
        UUID correctionRequired,
        UUID unresolvedFilter
    ) {
        public InputCut {
            observations = List.copyOf(observations);
        }

        public InputCut(
            UUID binding,
            long schemaRevision,
            UUID snapshot,
            UUID generation,
            String throughCheckpoint,
            List<Sample> observations,
            UUID unresolvedIdentity,
            UUID correctionRequired
        ) {
            this(
                binding,
                schemaRevision,
                snapshot,
                generation,
                throughCheckpoint,
                observations,
                unresolvedIdentity,
                correctionRequired,
                null
            );
        }
    }

    public record Result(
        VclDurationWitnessKernel.Result witness,
        ScalarValue absence,
        VclWindowPlan.Window window,
        UUID entry,
        UUID exit,
        List<UUID> positionWitnesses
    ) {
        public Result {
            positionWitnesses = List.copyOf(positionWitnesses);
        }

        private Result(VclDurationWitnessKernel.Result witness, ScalarValue absence) {
            this(witness, absence, null, null, null, List.of());
        }
    }

    private record Closed(
        KeyedStateStore.Key key,
        VclWindowPlan.Window window,
        UUID entry,
        UUID exit
    ) {}

    private record PositionEvidence(Wgs84ScalarKernel.Region region, UUID observation) {
        PositionEvidence merge(PositionEvidence other) {
            if (region != other.region) return new PositionEvidence(
                Wgs84ScalarKernel.Region.UNCERTAIN,
                observation
            );
            return observation.compareTo(other.observation) <= 0 ? this : other;
        }
    }

    public record Plan(
        String scope,
        String sessionDigest,
        VclWindowSessionKernel.Plan windowSession,
        ZoneReplay zoneReplay,
        VclCompilation.Dependency source,
        VclTimingPlan timing,
        VclDurationWitnessKernel.Plan witness,
        VclScalarProgram predicate,
        ScalarDecimalPolicy decimal,
        UUID subjectField,
        UUID occurrenceField,
        long maxGapUs,
        VclJournalInputPlan input
    ) {
        public Instant replayStart(Instant originalStart, Instant deploymentBoundary) {
            Instant earliest = originalStart.minus(maxDurationUs(), ChronoUnit.MICROS);
            if (zoneReplay != null) earliest = earliest.minus(
                zoneReplay.plan().maxGapUs(),
                ChronoUnit.MICROS
            );
            return earliest.isBefore(deploymentBoundary) ? deploymentBoundary : earliest;
        }

        public Instant replayEnd(Instant originalEnd) {
            return originalEnd.plus(maxDurationUs(), ChronoUnit.MICROS);
        }

        private long maxDurationUs() {
            return windowSession == null
                ? zoneReplay.plan().maxDurationUs()
                : windowSession.maxDurationUs();
        }
    }

    public record ZoneReplay(
        VclZoneSessionKernel.Plan plan,
        SpatialFeatureSnapshot geometry,
        UUID zoneField,
        UUID positionField,
        UUID accuracyField
    ) {}

    public static Plan compile(
        VclCompilation compiled,
        String absenceId,
        VclMetadata metadata,
        VclDurationWitnessKernel.HoldContract hold
    ) {
        return compile(compiled, absenceId, metadata, hold, null, null);
    }

    public static Plan compile(
        VclCompilation compiled,
        String absenceId,
        VclMetadata metadata,
        VclDurationWitnessKernel.HoldContract hold,
        SpatialFeatureSnapshot geometry,
        UUID zone
    ) {
        var document = PARSER.parse(compiled.canonicalDocument());
        require(
            compiled.accessScopeDigest().equals(metadata.accessScopeDigest()),
            "ACCESS_SCOPE_CHANGED"
        );
        var absence = node(compiled, absenceId, "absent_within");
        var witnessNode = node(compiled, absence.inputs().get("witnesses"), "observed_within");
        String sessionId = absence.inputs().get("sessions");
        require(
            sessionId != null && sessionId.equals(witnessNode.inputs().get("sessions")),
            "SESSION_MISMATCH"
        );
        var session = compiled
            .graph()
            .stream()
            .filter(
                node ->
                    sessionId.equals(node.id()) &&
                    List.of("window_session", "zone_session").contains(node.method())
            )
            .findFirst()
            .orElseThrow(() -> new VclException("SESSION_OPERATOR_REQUIRED", ""));
        var windowSession = "window_session".equals(session.method())
            ? VclWindowSessionKernel.compile(compiled, sessionId, metadata)
            : null;
        var config = session.config();
        ZoneReplay zoneReplay = null;
        if (windowSession == null) {
            var zonePlan = VclZoneSessionKernel.compile(compiled, sessionId, geometry, zone);
            String accuracy = config.path("accuracyFieldId").textValue();
            zoneReplay = new ZoneReplay(
                zonePlan,
                geometry,
                UUID.fromString(config.path("zoneFieldId").textValue()),
                UUID.fromString(config.path("positionFieldId").textValue()),
                accuracy == null ? null : UUID.fromString(accuracy)
            );
        }
        String sessionDigest =
            zoneReplay == null ? windowSession.digest() : zoneReplay.plan().digest();
        var input = VclJournalInputPlan.compile(compiled, session, metadata);
        var read = input.source();
        UUID binding = UUID.fromString(read.config().path("bindingId").textValue());
        long revision = read.config().path("schemaRevision").longValue();
        var timing = VclTimingPlan.compile(compiled);
        require(timing.requiredInputs().size() == 1, "SESSION_REFERENCE_COVERAGE_UNAVAILABLE");
        var source = timing.requiredInputs().iterator().next();
        require(
            source.kind() == VclMetadata.Resource.BINDING &&
                source.id().equals(binding) &&
                source.revision() == revision,
            "SESSION_SOURCE_CHANGED"
        );
        var fields = new LinkedHashMap<UUID, VclType>();
        session.fields().forEach((id, type) -> fields.put(UUID.fromString(id), type));
        var predicate = new VclScalarCompiler(PARSER, REGISTRY, UNITS).compile(
            witnessNode.config().path("predicate").toString(),
            fields,
            metadata
        );
        var decimal = document.spec().path("decimalPolicy");
        var policy = new ScalarDecimalPolicy(
            decimal.path("precision").intValue(),
            decimal.path("scale").intValue(),
            ScalarDecimalPolicy.Rounding.valueOf(decimal.path("rounding").textValue())
        );
        long gap = Long.parseLong(document.spec().at("/coverage/maxGapUs").textValue());
        if (
            hold == null && !"0".equals(witnessNode.config().path("minimumDurationUs").textValue())
        ) {
            var schema = metadata
                .binding(binding, revision)
                .orElseThrow(() -> new VclException("SESSION_SOURCE_CHANGED", ""));
            var ownerHold = VclSourceBindingProfile.sampleHoldUs(schema.definition());
            if (ownerHold.isPresent()) {
                hold = new VclDurationWitnessKernel.HoldContract(
                    VclSourceBindingProfile.SAMPLE_HOLD_VERSION,
                    Math.min(gap, ownerHold.getAsLong())
                );
            }
        }
        if (hold != null) gap = Math.min(gap, hold.maxHoldUs());
        return new Plan(
            compiled.accessScopeDigest(),
            sessionDigest,
            windowSession,
            zoneReplay,
            source,
            timing,
            VclDurationWitnessKernel.Plan.compile(compiled, witnessNode.id(), hold),
            predicate,
            policy,
            UUID.fromString(session.config().path("subjectFieldId").textValue()),
            UUID.fromString(session.config().path("occurrenceFieldId").textValue()),
            gap,
            input
        );
    }

    public Result evaluate(
        Plan plan,
        VclWindowSessionKernel.State session,
        InputCut input,
        VclSourceCoverage proof,
        String scope
    ) {
        authorizeSession(plan, session.planDigest(), scope);
        if (session.phase() != VclWindowSessionKernel.Phase.CLOSED) return unknown(
            "SESSION_NOT_OBSERVED_CLOSED"
        );
        if (input.correctionRequired() != null) {
            var replayed = replayWindow(plan, session.key(), input, scope);
            if (replayed == null) return correctionRequired(input);
            if (replayed.phase() == VclWindowSessionKernel.Phase.OPEN) return unknown(
                "SESSION_REPLAY_OPEN"
            );
            if (replayed.phase() != VclWindowSessionKernel.Phase.CLOSED) return correctionRequired(
                input
            );
            session = replayed;
            input = corrected(input);
        }
        return evaluateClosed(
            plan,
            new Closed(
                session.key(),
                new VclWindowPlan.Window(session.firstAt(), session.lastAt()),
                session.startWitness(),
                session.closeWitness()
            ),
            input,
            proof,
            scope
        );
    }

    private static VclWindowSessionKernel.State replayWindow(
        Plan plan,
        KeyedStateStore.Key key,
        InputCut input,
        String scope
    ) {
        if (
            plan.windowSession() == null ||
            input.unresolvedIdentity() != null ||
            input.unresolvedFilter() != null
        ) return null;
        var windowPlan = plan.windowSession();
        var kernel = new VclWindowSessionKernel();
        var state = VclWindowSessionKernel.empty(windowPlan, key);
        for (var row : ordered(input)) {
            if (
                plan.timing().classify(row.at(), row.recordedAt()) ==
                VclTimingPlan.Admission.QUARANTINED_FUTURE
            ) return null;
            state = kernel.observe(
                windowPlan,
                state,
                new VclWindowSessionKernel.Observation(row.id(), row.at(), row.fields()),
                scope
            );
        }
        return state;
    }

    public Result evaluate(
        Plan plan,
        VclZoneSessionKernel.State session,
        InputCut input,
        VclSourceCoverage proof,
        String scope
    ) {
        authorizeSession(plan, session.planDigest(), scope);
        if (session.phase() != VclZoneSessionKernel.Phase.CLOSED) return unknown(
            "SESSION_NOT_OBSERVED_CLOSED"
        );
        if (input.correctionRequired() != null) {
            var replayed = replayZone(plan, session.key(), input);
            if (replayed == null) return correctionRequired(input);
            if (replayed.phase() == VclZoneSessionKernel.Phase.OPEN) return unknown(
                "SESSION_REPLAY_OPEN"
            );
            if (replayed.phase() != VclZoneSessionKernel.Phase.CLOSED) return correctionRequired(
                input
            );
            session = replayed;
            input = corrected(input);
        }
        return evaluateClosed(
            plan,
            new Closed(
                session.key(),
                new VclWindowPlan.Window(session.enteredAt(), session.lastAt()),
                session.entryWitness(),
                session.exitWitness()
            ),
            input,
            proof,
            scope
        );
    }

    private static VclZoneSessionKernel.State replayZone(
        Plan plan,
        KeyedStateStore.Key key,
        InputCut input
    ) {
        var replay = plan.zoneReplay();
        if (
            replay == null || input.unresolvedIdentity() != null || input.unresolvedFilter() != null
        ) return null;
        var kernel = new VclZoneSessionKernel();
        var state = VclZoneSessionKernel.empty(replay.plan(), key);
        for (var row : ordered(input)) {
            if (
                plan.timing().classify(row.at(), row.recordedAt()) ==
                VclTimingPlan.Admission.QUARANTINED_FUTURE
            ) return null;
            state = kernel.observe(
                replay.plan(),
                state,
                zoneObservation(replay, row),
                replay.geometry()
            );
        }
        return state;
    }

    public Result preview(
        Plan plan,
        KeyedStateStore.Key key,
        InputCut input,
        VclSourceCoverage proof,
        String scope
    ) {
        require(plan.scope().equals(scope), "ACCESS_SCOPE_CHANGED");
        require(
            plan.source().id().equals(input.binding()) &&
                plan.source().revision() == input.schemaRevision(),
            "SESSION_SOURCE_CHANGED"
        );
        if (
            input.unresolvedIdentity() != null ||
            input.unresolvedFilter() != null ||
            input.correctionRequired() != null
        ) {
            return correctionRequired(input);
        }
        if (plan.zoneReplay() != null) {
            var zone = replayZone(plan, key, input);
            return zone == null
                ? unknown("SESSION_FUTURE_OBSERVATION")
                : evaluate(plan, zone, input, proof, scope);
        }
        var session = replayWindow(plan, key, input, scope);
        return session == null
            ? unknown("SESSION_FUTURE_OBSERVATION")
            : evaluate(plan, session, input, proof, scope);
    }

    private static List<Sample> ordered(InputCut input) {
        require(
            input.observations().size() <= VclDurationWitnessKernel.MAX_INPUTS,
            "WITNESS_INPUT_LIMIT"
        );
        return input
            .observations()
            .stream()
            .sorted(Comparator.comparing(Sample::at).thenComparing(Sample::id))
            .toList();
    }

    private static InputCut corrected(InputCut input) {
        return new InputCut(
            input.binding(),
            input.schemaRevision(),
            input.snapshot(),
            input.generation(),
            input.throughCheckpoint(),
            input.observations(),
            null,
            null
        );
    }

    private Result evaluateClosed(
        Plan plan,
        Closed closed,
        InputCut input,
        VclSourceCoverage proof,
        String scope
    ) {
        var result = evaluate(plan, closed, input, proof, scope);
        return new Result(
            result.witness(),
            result.absence(),
            closed.window(),
            closed.entry(),
            closed.exit(),
            result.positionWitnesses()
        );
    }

    private Result evaluate(
        Plan plan,
        Closed closed,
        InputCut input,
        VclSourceCoverage proof,
        String scope
    ) {
        require(
            plan.source().id().equals(input.binding()) &&
                plan.source().revision() == input.schemaRevision(),
            "SESSION_SOURCE_CHANGED"
        );
        if (input.correctionRequired() != null) {
            return correctionRequired(input);
        }
        require(
            input.observations().size() <= VclDurationWitnessKernel.MAX_INPUTS,
            "WITNESS_INPUT_LIMIT"
        );
        var matching = new ArrayList<Sample>();
        boolean entry = false;
        boolean exit = false;
        for (var row : input.observations()) {
            if (
                row.at().isBefore(closed.window().startInclusive()) ||
                row.at().isAfter(closed.window().endExclusive())
            ) continue;
            require(
                closed.key().equals(row.key()) &&
                    closed
                        .key()
                        .subjectDigest()
                        .equals(
                            VclWindowSessionKernel.identity(row.fields().get(plan.subjectField()))
                        ) &&
                    closed
                        .key()
                        .occurrenceDigest()
                        .equals(
                            VclWindowSessionKernel.identity(
                                row.fields().get(plan.occurrenceField())
                            )
                        ),
                "SESSION_OCCURRENCE_CHANGED"
            );
            if (
                plan.timing().classify(row.at(), row.recordedAt()) ==
                VclTimingPlan.Admission.QUARANTINED_FUTURE
            ) {
                return unknown("SESSION_FUTURE_OBSERVATION");
            }
            entry |=
                closed.entry().equals(row.id()) &&
                closed.window().startInclusive().equals(row.at());
            exit |=
                closed.exit().equals(row.id()) && closed.window().endExclusive().equals(row.at());
            matching.add(row);
        }
        if (!entry || !exit) return unknown("SESSION_BOUNDARY_WITNESS_MISSING");
        var positions = new HashMap<Instant, PositionEvidence>();
        if (plan.zoneReplay() != null) {
            var replay = plan.zoneReplay();
            for (var row : matching) {
                var observation = zoneObservation(replay, row);
                if (observation.position() != null) {
                    var region = replay.plan().circle().id().equals(observation.zone())
                        ? VclZoneSessionKernel.classify(replay.plan(), observation)
                        : Wgs84ScalarKernel.Region.UNCERTAIN;
                    var evidence = new PositionEvidence(region, row.id());
                    positions.merge(row.at(), evidence, PositionEvidence::merge);
                }
            }
            var entryPosition = positions.get(closed.window().startInclusive());
            var exitPosition = positions.get(closed.window().endExclusive());
            if (
                entryPosition == null ||
                entryPosition.region() != Wgs84ScalarKernel.Region.INSIDE ||
                exitPosition == null ||
                exitPosition.region() != Wgs84ScalarKernel.Region.OUTSIDE
            ) {
                return unknown("SESSION_BOUNDARY_POSITION_UNKNOWN");
            }
        }
        var qualifiedPositions = new HashMap<UUID, UUID>();
        var rows = new ArrayList<VclDurationWitnessKernel.Input>();
        for (var row : matching) {
            var predicate = evaluator.evaluate(
                plan.predicate(),
                row.fields(),
                plan.decimal(),
                scope
            );
            if (plan.zoneReplay() != null) {
                var position = positions.get(row.at());
                predicate = qualifyZoneWitness(plan.zoneReplay(), row, predicate, position);
                if (
                    position != null &&
                    predicate.status() == ScalarValue.Status.OK &&
                    predicate.literal().path("value").booleanValue()
                ) {
                    qualifiedPositions.put(row.id(), position.observation());
                }
            }
            rows.add(new VclDurationWitnessKernel.Input(row.id(), row.key(), row.at(), predicate));
        }
        var context = new VclDurationWitnessKernel.Context(
            VclDurationWitnessKernel.VERSION,
            plan.witness().digest(),
            scope,
            closed.key(),
            closed.window()
        );
        var witness = new VclDurationWitnessKernel().evaluate(plan.witness(), context, rows, scope);
        if (witness.value().isFailure()) return new Result(witness, witness.value());
        if (witness.value().status() == ScalarValue.Status.OK) {
            var spatial = java.util.stream.Stream.of(
                witness.firstObservationId(),
                witness.lastObservationId()
            )
                .map(qualifiedPositions::get)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
            return new Result(witness, truth(false), null, null, null, spatial);
        }
        if (input.unresolvedIdentity() != null) {
            return new Result(
                witness,
                ScalarValue.unknown("SESSION_IDENTITY_UNKNOWN", List.of(input.unresolvedIdentity()))
            );
        }
        if (input.unresolvedFilter() != null) {
            return new Result(
                witness,
                ScalarValue.unknown("SESSION_FILTER_UNKNOWN", List.of(input.unresolvedFilter()))
            );
        }
        if (!witness.unknownEvidence().isEmpty()) {
            return new Result(
                witness,
                ScalarValue.unknown("SESSION_PREDICATE_UNKNOWN", witness.unknownEvidence())
            );
        }
        if (!qualified(plan, closed.window(), input, proof)) {
            return new Result(witness, ScalarValue.unknown("SESSION_COVERAGE_UNKNOWN", List.of()));
        }
        if (
            !continuous(
                rows.stream().map(VclDurationWitnessKernel.Input::eventTime).toList(),
                plan.maxGapUs()
            )
        ) {
            return new Result(witness, ScalarValue.unknown("SESSION_SOURCE_GAP", List.of()));
        }
        if (
            plan.zoneReplay() != null &&
            !continuousPositions(positions, plan.zoneReplay().plan().maxGapUs())
        ) {
            return new Result(witness, ScalarValue.unknown("SESSION_POSITION_GAP", List.of()));
        }
        return new Result(witness, truth(true));
    }

    private static ScalarValue qualifyZoneWitness(
        ZoneReplay replay,
        Sample row,
        ScalarValue predicate,
        PositionEvidence position
    ) {
        if (
            predicate.isFailure() ||
            (predicate.status() == ScalarValue.Status.OK &&
                !predicate.isNull() &&
                !predicate.literal().path("value").booleanValue())
        ) return predicate;
        if (
            position == null ||
            !replay.plan().circle().id().equals(zoneObservation(replay, row).zone())
        ) {
            return ScalarValue.unknown("SESSION_WITNESS_POSITION_UNKNOWN", List.of(row.id()));
        }
        return switch (position.region()) {
            case INSIDE -> predicate;
            case OUTSIDE -> truth(false);
            case UNCERTAIN -> ScalarValue.unknown(
                "SESSION_WITNESS_POSITION_UNKNOWN",
                List.of(row.id())
            );
        };
    }

    private static VclZoneSessionKernel.Observation zoneObservation(ZoneReplay replay, Sample row) {
        var fields = row.fields();
        return VclZoneSessionKernel.observation(
            row.id(),
            row.at(),
            row.key(),
            fields.get(replay.zoneField()),
            fields.get(replay.positionField()),
            replay.accuracyField() == null ? null : fields.get(replay.accuracyField())
        );
    }

    private static Result correctionRequired(InputCut input) {
        if (input.unresolvedIdentity() != null) {
            return new Result(
                null,
                ScalarValue.unknown("SESSION_IDENTITY_UNKNOWN", List.of(input.unresolvedIdentity()))
            );
        }
        if (input.unresolvedFilter() != null) {
            return new Result(
                null,
                ScalarValue.unknown("SESSION_FILTER_UNKNOWN", List.of(input.unresolvedFilter()))
            );
        }
        return new Result(
            null,
            ScalarValue.unknown("SESSION_CORRECTION_REQUIRED", List.of(input.correctionRequired()))
        );
    }

    private static void authorizeSession(Plan plan, String sessionDigest, String scope) {
        require(plan.scope().equals(scope), "ACCESS_SCOPE_CHANGED");
        require(plan.sessionDigest().equals(sessionDigest), "SESSION_PLAN_CHANGED");
    }

    private static boolean qualified(
        Plan plan,
        VclWindowPlan.Window window,
        InputCut input,
        VclSourceCoverage proof
    ) {
        if (
            proof == null ||
            !proof.qualifiesEventTime() ||
            !proof.snapshotId().equals(input.snapshot()) ||
            !proof.inputGeneration().equals(input.generation()) ||
            proof.evidence().from().isAfter(window.startInclusive()) ||
            proof
                .evidence()
                .until()
                .isBefore(plan.timing().windowCloseDueAt(window.endExclusive())) ||
            VclObservationReadiness.checkpoint(proof.requiredCheckpoint()) >
                VclObservationReadiness.checkpoint(input.throughCheckpoint())
        ) return false;
        var frontier = plan
            .timing()
            .watermark(
                List.of(
                    new VclTimingPlan.Progress(
                        plan.source(),
                        proof.evidence().until(),
                        proof.acceptedAt()
                    )
                ),
                plan.timing().digest(),
                plan.scope()
            );
        return (
            frontier.status() == ScalarValue.Status.OK &&
            !Instant.parse(frontier.literal().path("value").textValue()).isBefore(
                window.endExclusive()
            )
        );
    }

    private static boolean continuous(List<Instant> evidenceTimes, long maxGap) {
        var times = evidenceTimes.stream().sorted().toList();
        for (int i = 1; i < times.size(); i++) {
            if (
                VclWindowPlan.micros(times.get(i)) - VclWindowPlan.micros(times.get(i - 1)) > maxGap
            ) return false;
        }
        return true;
    }

    private static boolean continuousPositions(
        Map<Instant, PositionEvidence> positions,
        long maxGap
    ) {
        var qualified = positions
            .entrySet()
            .stream()
            .filter(position -> position.getValue().region() != Wgs84ScalarKernel.Region.UNCERTAIN)
            .map(Map.Entry::getKey)
            .toList();
        return continuous(qualified, maxGap);
    }

    private static VclCompilation.Node node(VclCompilation compiled, String id, String method) {
        return compiled
            .graph()
            .stream()
            .filter(node -> node.id().equals(id) && method.equals(node.method()))
            .findFirst()
            .orElseThrow(() -> new VclException("SESSION_GRAPH_UNSUPPORTED", ""));
    }

    private static Result unknown(String reason) {
        return new Result(null, ScalarValue.unknown(reason, List.of()));
    }

    private static ScalarValue truth(boolean value) {
        return ScalarValue.known(
            JsonNodeFactory.instance.objectNode().put("type", "BOOLEAN").put("value", value)
        );
    }

    private static void require(boolean valid, String code) {
        if (!valid) throw new VclException(code, "");
    }
}
