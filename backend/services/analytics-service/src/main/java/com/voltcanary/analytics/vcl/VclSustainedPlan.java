package com.voltcanary.analytics.vcl;

import com.voltcanary.vcl.VclCompilation;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclMetadata;
import com.voltcanary.vcl.VclParser;
import com.voltcanary.vcl.VclRegistry;
import com.voltcanary.vcl.VclScalarCompiler;
import com.voltcanary.vcl.VclScalarProgram;
import com.voltcanary.vcl.VclSourceBindingProfile;
import com.voltcanary.vcl.VclType;
import com.voltcanary.vcl.VclUnits;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Typed sustained evaluation over an exact subject and bounded observation interval. */
public final class VclSustainedPlan {

    private static final VclParser PARSER = new VclParser();
    private static final VclRegistry REGISTRY = new VclRegistry(PARSER);
    private static final VclUnits UNITS = VclUnits.standard();
    private final VclScalarEvaluator evaluator = new VclScalarEvaluator(UNITS, REGISTRY.digest());

    public record Sample(UUID id, Instant at, Instant recordedAt, Map<UUID, ScalarValue> fields) {
        public Sample {
            fields = Map.copyOf(fields);
        }
    }

    private final String scope;
    private final UUID subjectField;
    private final UUID binding;
    private final long schemaRevision;
    private final VclTimingPlan timing;
    private final VclDurationWitnessKernel.Plan witness;
    private final VclScalarProgram predicate;
    private final ScalarDecimalPolicy decimal;
    private final VclJournalInputPlan input;

    private VclSustainedPlan(
        String scope,
        UUID subjectField,
        UUID binding,
        long schemaRevision,
        VclTimingPlan timing,
        VclDurationWitnessKernel.Plan witness,
        VclScalarProgram predicate,
        ScalarDecimalPolicy decimal,
        VclJournalInputPlan input
    ) {
        this.scope = scope;
        this.subjectField = subjectField;
        this.binding = binding;
        this.schemaRevision = schemaRevision;
        this.timing = timing;
        this.witness = witness;
        this.predicate = predicate;
        this.decimal = decimal;
        this.input = input;
    }

    public static VclSustainedPlan compile(
        VclCompilation compilation,
        String nodeId,
        VclMetadata owner
    ) {
        String scope = compilation.accessScopeDigest();
        if (!scope.equals(owner.accessScopeDigest())) throw new VclException(
            "ACCESS_SCOPE_CHANGED",
            ""
        );
        var node = node(compilation, nodeId, "sustained");
        var input = VclJournalInputPlan.compile(compilation, node, owner);
        var source = input.source();
        UUID binding = UUID.fromString(source.config().path("bindingId").textValue());
        long revision = source.config().path("schemaRevision").longValue();
        var schema = owner
            .binding(binding, revision)
            .orElseThrow(() -> new VclException("SOURCE_CAPTURE_UNAVAILABLE", ""));
        long ownerHold = VclSourceBindingProfile.sampleHoldUs(schema.definition()).orElseThrow(() ->
            new VclException("WITNESS_HOLD_REQUIRED", "")
        );
        long gap = Long.parseLong(node.config().path("maxGapUs").textValue());
        var contract = new VclDurationWitnessKernel.HoldContract(
            VclSourceBindingProfile.SAMPLE_HOLD_VERSION,
            Math.min(gap, ownerHold)
        );
        var witness = VclDurationWitnessKernel.Plan.compile(compilation, nodeId, contract);
        var types = new LinkedHashMap<UUID, VclType>();
        source.fields().forEach((id, type) -> types.put(UUID.fromString(id), type));
        var predicate = new VclScalarCompiler(PARSER, REGISTRY, UNITS).compile(
            node.config().path("predicate").toString(),
            types,
            owner
        );
        var policy = PARSER.parse(compilation.canonicalDocument()).spec().path("decimalPolicy");
        var decimal = new ScalarDecimalPolicy(
            policy.path("precision").intValue(),
            policy.path("scale").intValue(),
            ScalarDecimalPolicy.Rounding.valueOf(policy.path("rounding").textValue())
        );
        return new VclSustainedPlan(
            scope,
            UUID.fromString(node.config().path("subjectFieldId").textValue()),
            binding,
            revision,
            VclTimingPlan.compile(compilation),
            witness,
            predicate,
            decimal,
            input
        );
    }

    public UUID subjectField() {
        return subjectField;
    }

    public UUID binding() {
        return binding;
    }

    public long schemaRevision() {
        return schemaRevision;
    }

    public String digest() {
        return witness.digest();
    }

    public VclTimingPlan timing() {
        return timing;
    }

    public boolean excludes(Sample row) {
        return input.select(row.fields()) == VclJournalInputPlan.Selection.EXCLUDED;
    }

    enum Truth {
        TRUE,
        FALSE,
        UNKNOWN,
    }

    record Point(UUID id, Instant at, Truth truth) {}

    record Cursor(Point pending, UUID start, Instant startedAt, boolean emitted) {
        static Cursor empty() {
            return new Cursor(null, null, null, false);
        }
    }

    record Advance(Cursor cursor, VclDurationWitnessKernel.Episode episode) {}

    Point point(KeyedStateStore.Key key, Sample row, String authorizedScope) {
        if (!scope.equals(authorizedScope)) throw new VclException("ACCESS_SCOPE_CHANGED", "");
        if (
            !witness.ruleId().equals(key.ruleId()) ||
            !key
                .subjectDigest()
                .equals(VclWindowSessionKernel.identity(row.fields().get(subjectField)))
        ) {
            throw new VclException("WITNESS_PARTITION_CHANGED", "");
        }
        VclWindowPlan.micros(row.at());
        if (
            timing.classify(row.at(), row.recordedAt()) ==
            VclTimingPlan.Admission.QUARANTINED_FUTURE
        ) return null;
        var selection = input.select(row.fields());
        if (selection == VclJournalInputPlan.Selection.EXCLUDED) return null;
        var value = evaluatePredicate(row, authorizedScope, selection);
        if (value.isFailure()) throw new VclException(value.reason(), "");
        var truth =
            value.status() == ScalarValue.Status.UNKNOWN || value.isNull()
                ? Truth.UNKNOWN
                : value.literal().path("value").booleanValue()
                  ? Truth.TRUE
                  : Truth.FALSE;
        return new Point(row.id(), row.at(), truth);
    }

    private ScalarValue evaluatePredicate(
        Sample row,
        String authorizedScope,
        VclJournalInputPlan.Selection selection
    ) {
        return selection == VclJournalInputPlan.Selection.UNKNOWN
            ? ScalarValue.unknown("SUSTAINED_FILTER_UNKNOWN", List.of(row.id()))
            : evaluator.evaluate(predicate, row.fields(), decimal, authorizedScope);
    }

    Advance advance(KeyedStateStore.Key key, Cursor before, Point next) {
        if (next == null) return new Advance(before, null);
        var previous = before.pending();
        if (previous == null) return new Advance(new Cursor(next, null, null, false), null);
        long gap = VclWindowPlan.micros(next.at()) - VclWindowPlan.micros(previous.at());
        if (gap < 0) throw new VclException("SUSTAINED_CORRECTION_REQUIRED", "");
        if (gap == 0) {
            var first =
                next.id().toString().compareTo(previous.id().toString()) < 0
                    ? next.id()
                    : previous.id();
            var truth = previous.truth() == next.truth() ? next.truth() : Truth.UNKNOWN;
            return new Advance(
                new Cursor(
                    new Point(first, next.at(), truth),
                    before.start(),
                    before.startedAt(),
                    before.emitted()
                ),
                null
            );
        }
        VclDurationWitnessKernel.Episode episode = null;
        if (before.start() != null && !before.emitted()) {
            long covered =
                VclWindowPlan.micros(previous.at()) - VclWindowPlan.micros(before.startedAt());
            if (covered >= witness.minimumUs()) {
                episode = new VclDurationWitnessKernel.Episode(
                    witness.occurrenceDigest(key, before.start()),
                    before.start(),
                    before.startedAt(),
                    previous.id(),
                    previous.at(),
                    covered
                );
            }
        }
        if (previous.truth() != Truth.TRUE || gap > witness.holdUs()) {
            return new Advance(new Cursor(next, null, null, false), episode);
        }
        return new Advance(
            new Cursor(
                next,
                before.start() == null ? previous.id() : before.start(),
                before.startedAt() == null ? previous.at() : before.startedAt(),
                before.emitted() || episode != null
            ),
            episode
        );
    }

    public VclDurationWitnessKernel.Result evaluate(
        KeyedStateStore.Key key,
        VclWindowPlan.Window window,
        List<Sample> observations,
        String authorizedScope
    ) {
        return episodes(key, window, observations, authorizedScope).result();
    }

    public VclDurationWitnessKernel.Episodes episodes(
        KeyedStateStore.Key key,
        VclWindowPlan.Window window,
        List<Sample> observations,
        String authorizedScope
    ) {
        if (!scope.equals(authorizedScope)) throw new VclException("ACCESS_SCOPE_CHANGED", "");
        if (observations.size() > VclDurationWitnessKernel.MAX_INPUTS) throw new VclException(
            "WITNESS_INPUT_LIMIT",
            ""
        );
        var inputs = new ArrayList<VclDurationWitnessKernel.Input>(observations.size());
        for (var row : observations) {
            if (
                !key
                    .subjectDigest()
                    .equals(VclWindowSessionKernel.identity(row.fields().get(subjectField)))
            ) {
                throw new VclException("WITNESS_PARTITION_CHANGED", "");
            }
            var selection = input.select(row.fields());
            if (selection == VclJournalInputPlan.Selection.EXCLUDED) continue;
            if (
                timing.classify(row.at(), row.recordedAt()) ==
                VclTimingPlan.Admission.QUARANTINED_FUTURE
            ) {
                var evidence = List.of(row.id());
                return new VclDurationWitnessKernel.Episodes(
                    new VclDurationWitnessKernel.Result(
                        ScalarValue.unknown("WITNESS_FUTURE_OBSERVATION", evidence),
                        0,
                        null,
                        null,
                        evidence
                    ),
                    List.of()
                );
            }
            var value = evaluatePredicate(row, authorizedScope, selection);
            inputs.add(new VclDurationWitnessKernel.Input(row.id(), key, row.at(), value));
        }
        var context = new VclDurationWitnessKernel.Context(
            VclDurationWitnessKernel.VERSION,
            witness.digest(),
            scope,
            key,
            window
        );
        return new VclDurationWitnessKernel().evaluateEpisodes(
            witness,
            context,
            inputs,
            authorizedScope
        );
    }

    private static VclCompilation.Node node(VclCompilation compilation, String id, String method) {
        return compilation
            .graph()
            .stream()
            .filter(node -> node.id().equals(id) && method.equals(node.method()))
            .findFirst()
            .orElseThrow(() -> new VclException("SUSTAINED_DIRECT_JOURNAL_REQUIRED", ""));
    }
}
