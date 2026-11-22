package com.voltcanary.analytics.vcl;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclCompilation;
import com.voltcanary.vcl.VclDocument;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclParser;
import com.voltcanary.vcl.VclRegistry;
import com.voltcanary.vcl.VclUnits;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/** Bounded supplied-batch witness arithmetic. It supplies no source coverage, finalization or effect permit. */
public final class VclDurationWitnessKernel {

    public static final String VERSION = "vcl-duration-witness-1";
    public static final int MAX_INPUTS = 4096;
    private static final VclParser PARSER = new VclParser();
    private static final String REGISTRY = new VclRegistry(PARSER).digest();

    /** An explicitly approved owner hold contract, not a policy inferred from observation spacing. */
    public record HoldContract(String version, long maxHoldUs) {
        public HoldContract {
            if (
                !com.voltcanary.vcl.VclSourceBindingProfile.SAMPLE_HOLD_VERSION.equals(version) ||
                maxHoldUs < 1
            ) {
                throw new IllegalArgumentException("Invalid hold contract");
            }
        }
    }

    public static final class Plan {

        private final long minimumUs;
        private final long holdUs;
        private final String scope;
        private final String digest;
        private final UUID ruleId;
        private final String nodeId;

        private Plan(VclCompilation compilation, String nodeId, HoldContract hold) {
            this.nodeId = nodeId;
            require(
                compilation != null && compilation.kind() == VclDocument.Kind.RULE,
                "RULE_REQUIRED"
            );
            require(
                REGISTRY.equals(compilation.registryDigest()) &&
                    VclUnits.standard().digest().equals(compilation.unitRegistryDigest()),
                "REGISTRY_VERSION_UNAVAILABLE"
            );
            var document = PARSER.parse(compilation.canonicalDocument());
            ruleId = UUID.fromString(document.spec().path("ruleId").asText());
            require(
                compilation.documentDigest().equals(VclCanonical.documentDigest(document)) &&
                    compilation.semanticDigest().equals(VclCanonical.semanticDigest(document)),
                "WITNESS_DOCUMENT_CHANGED"
            );
            var matches = compilation
                .graph()
                .stream()
                .filter(node -> node.id().equals(nodeId))
                .toList();
            require(matches.size() == 1, "WITNESS_OPERATOR_REQUIRED");
            var node = matches.getFirst();
            require(
                Set.of("sustained", "observed_within").contains(node.method()),
                "WITNESS_OPERATOR_REQUIRED"
            );
            var authored = java.util.stream.StreamSupport.stream(
                document.spec().path("nodes").spliterator(),
                false
            )
                .filter(value -> value.path("id").asText().equals(nodeId))
                .findFirst()
                .orElseThrow(() -> new VclException("WITNESS_DOCUMENT_CHANGED", ""));
            require(
                node.method().equals(authored.path("method").asText()) &&
                    VclCanonical.encode(node.config()).equals(
                        VclCanonical.encode(authored.path("config"))
                    ),
                "WITNESS_DOCUMENT_CHANGED"
            );
            minimumUs = duration(node.config().path("minimumDurationUs").asText());
            long rootGap = duration(document.spec().at("/coverage/maxGapUs").asText());
            long gap = node.method().equals("sustained")
                ? duration(node.config().path("maxGapUs").asText())
                : rootGap;
            require(
                rootGap > 0 &&
                    gap > 0 &&
                    gap <= rootGap &&
                    (!node.method().equals("sustained") || minimumUs > 0),
                "WITNESS_GAP_POLICY_INVALID"
            );
            require(minimumUs == 0 || hold != null, "WITNESS_HOLD_REQUIRED");
            require(hold == null || hold.maxHoldUs() <= gap, "WITNESS_HOLD_EXCEEDS_GAP");
            holdUs = hold == null ? 0 : hold.maxHoldUs();
            scope = compilation.accessScopeDigest();
            require(scope != null && scope.matches("[0-9a-f]{64}"), "ACCESS_SCOPE_REQUIRED");
            digest = VclCanonical.sha256(
                VERSION +
                    "\n" +
                    compilation.semanticDigest() +
                    "\n" +
                    nodeId +
                    "\n" +
                    scope +
                    "\n" +
                    VclCanonical.encode(node.config()) +
                    "\n" +
                    hold
            );
        }

        public static Plan compile(VclCompilation compilation, String nodeId, HoldContract hold) {
            return new Plan(compilation, nodeId, hold);
        }

        public String digest() {
            return digest;
        }

        public UUID ruleId() {
            return ruleId;
        }

        long minimumUs() {
            return minimumUs;
        }

        long holdUs() {
            return holdUs;
        }

        String occurrenceDigest(KeyedStateStore.Key key, UUID start) {
            return VclCanonical.sha256(
                String.join(
                    "\n",
                    "VCL_SUSTAINED_OCCURRENCE/1",
                    key.accountId().toString(),
                    key.ruleId().toString(),
                    nodeId,
                    key.subjectDigest(),
                    start.toString()
                )
            );
        }

        @Override
        public String toString() {
            return "DurationWitnessPlan[]";
        }
    }

    /** Predicate values have already been evaluated by the common typed scalar path. */
    public record Input(
        UUID observationId,
        KeyedStateStore.Key partition,
        Instant eventTime,
        ScalarValue predicate
    ) {
        public Input {
            Objects.requireNonNull(observationId);
            Objects.requireNonNull(partition);
            Objects.requireNonNull(eventTime);
            Objects.requireNonNull(predicate);
        }

        @Override
        public String toString() {
            return "DurationWitnessInput[]";
        }
    }

    public record Context(
        String kernelVersion,
        String planDigest,
        String scopeDigest,
        KeyedStateStore.Key partition,
        VclWindowPlan.Window window
    ) {
        public Context {
            Objects.requireNonNull(kernelVersion);
            require(
                planDigest != null &&
                    planDigest.matches("[0-9a-f]{64}") &&
                    scopeDigest != null &&
                    scopeDigest.matches("[0-9a-f]{64}"),
                "WITNESS_CONTEXT_INVALID"
            );
            Objects.requireNonNull(partition);
            Objects.requireNonNull(window);
            require(
                VclWindowPlan.micros(window.endExclusive()) -
                    VclWindowPlan.micros(window.startInclusive()) <=
                    32L * 86_400_000_000L,
                "WITNESS_WINDOW_LIMIT"
            );
        }

        @Override
        public String toString() {
            return "DurationWitnessContext[]";
        }
    }

    /** UNKNOWN means no witness was proven in this supplied batch; it never establishes absence. */
    public record Result(
        ScalarValue value,
        long coveredDurationUs,
        UUID firstObservationId,
        UUID lastObservationId,
        List<UUID> unknownEvidence
    ) {
        public Result {
            unknownEvidence = List.copyOf(unknownEvidence);
        }

        @Override
        public String toString() {
            return "DurationWitnessResult[status=" + value.status() + "]";
        }
    }

    public record Episode(
        String occurrenceDigest,
        UUID firstObservation,
        Instant startedAt,
        UUID qualifyingObservation,
        Instant qualifiedAt,
        long coveredDurationUs
    ) {}

    public record Episodes(Result result, List<Episode> occurrences) {
        public Episodes {
            occurrences = List.copyOf(occurrences);
        }
    }

    private enum Truth {
        TRUE,
        FALSE,
        UNKNOWN,
    }

    private record Group(Instant time, Truth truth, UUID firstId, List<UUID> ids) {}

    public Result evaluate(
        Plan plan,
        Context context,
        List<Input> inputs,
        String authorizedScopeDigest
    ) {
        return evaluate(plan, context, inputs, authorizedScopeDigest, null);
    }

    public Episodes evaluateEpisodes(
        Plan plan,
        Context context,
        List<Input> inputs,
        String authorizedScopeDigest
    ) {
        require(plan.minimumUs > 0, "WITNESS_POSITIVE_DURATION_REQUIRED");
        var episodes = new ArrayList<Episode>();
        var result = evaluate(plan, context, inputs, authorizedScopeDigest, episodes);
        return new Episodes(result, result.value().isFailure() ? List.of() : episodes);
    }

    private Result evaluate(
        Plan plan,
        Context context,
        List<Input> inputs,
        String authorizedScopeDigest,
        List<Episode> episodes
    ) {
        if (
            !plan.scope.equals(authorizedScopeDigest) || !plan.scope.equals(context.scopeDigest())
        ) return failure(ScalarValue.Status.ACCESS_REVOKED, "ACCESS_SCOPE_CHANGED");
        if (!VERSION.equals(context.kernelVersion())) return failure(
            ScalarValue.Status.SCHEMA_UNAVAILABLE,
            "WITNESS_KERNEL_UNAVAILABLE"
        );
        if (!plan.digest.equals(context.planDigest())) return failure(
            ScalarValue.Status.SCHEMA_UNAVAILABLE,
            "WITNESS_PLAN_CHANGED"
        );
        if (!plan.ruleId.equals(context.partition().ruleId())) return failure(
            ScalarValue.Status.SCHEMA_UNAVAILABLE,
            "WITNESS_RULE_CHANGED"
        );
        if (inputs == null) return failure(
            ScalarValue.Status.INVALID_INPUT,
            "WITNESS_INPUT_REQUIRED"
        );
        if (inputs.size() > MAX_INPUTS) return failure(
            ScalarValue.Status.STATE_LIMIT,
            "WITNESS_INPUT_LIMIT"
        );
        var sorted = new ArrayList<Input>(inputs.size());
        var ids = new HashSet<UUID>();
        try {
            for (var input : inputs) {
                require(input != null, "WITNESS_INPUT_REQUIRED");
                require(ids.add(input.observationId()), "WITNESS_INPUT_DUPLICATE");
                require(context.partition().equals(input.partition()), "WITNESS_PARTITION_CHANGED");
                VclWindowPlan.micros(input.eventTime());
                // End-exclusive observations can close a preceding hold, but cannot themselves supply an eligible witness.
                require(
                    !input.eventTime().isBefore(context.window().startInclusive()) &&
                        !input.eventTime().isAfter(context.window().endExclusive()),
                    "WITNESS_OUTSIDE_WINDOW"
                );
                sorted.add(input);
            }
            sorted.sort(
                Comparator.comparing(Input::eventTime).thenComparing(input ->
                    input.observationId().toString()
                )
            );
            for (var input : sorted)
                if (input.predicate().isFailure()) return new Result(
                    input.predicate(),
                    0,
                    null,
                    null,
                    List.of()
                );
            return reduce(plan, context, sorted, episodes);
        } catch (VclException error) {
            return failure(ScalarValue.Status.INVALID_INPUT, error.code());
        }
    }

    private Result reduce(Plan plan, Context context, List<Input> sorted, List<Episode> episodes) {
        var evidence = new TreeSet<UUID>(Comparator.comparing(UUID::toString));
        var groups = new ArrayList<Group>();
        UUID zero = null;
        for (int offset = 0; offset < sorted.size(); ) {
            var first = sorted.get(offset);
            boolean yes = false,
                no = false,
                unknown = false;
            var groupIds = new ArrayList<UUID>();
            int next = offset;
            while (next < sorted.size() && first.eventTime().equals(sorted.get(next).eventTime())) {
                var input = sorted.get(next++);
                var truth = truth(input.predicate());
                if (
                    truth == Truth.TRUE &&
                    context.window().contains(input.eventTime()) &&
                    zero == null
                ) zero = input.observationId();
                yes |= truth == Truth.TRUE;
                no |= truth == Truth.FALSE;
                unknown |= truth == Truth.UNKNOWN;
                groupIds.add(input.observationId());
                if (truth == Truth.UNKNOWN) evidence.addAll(input.predicate().evidence());
            }
            Truth truth = unknown || (yes && no) ? Truth.UNKNOWN : yes ? Truth.TRUE : Truth.FALSE;
            if (truth == Truth.UNKNOWN) evidence.addAll(groupIds);
            groups.add(
                new Group(first.eventTime(), truth, first.observationId(), List.copyOf(groupIds))
            );
            offset = next;
        }
        long run = 0,
            longest = 0;
        UUID runStart = null,
            bestStart = null,
            bestEnd = null;
        Instant startedAt = null;
        for (int i = 0; i < groups.size(); i++) {
            var current = groups.get(i);
            if (i > 0) {
                var previous = groups.get(i - 1);
                long elapsed =
                    VclWindowPlan.micros(current.time()) - VclWindowPlan.micros(previous.time());
                if (previous.truth() == Truth.TRUE && elapsed <= plan.holdUs) {
                    if (runStart == null) {
                        runStart = previous.firstId();
                        startedAt = previous.time();
                    }
                    long priorRun = run;
                    run += elapsed;
                    if (episodes != null && priorRun < plan.minimumUs && run >= plan.minimumUs) {
                        String occurrence = plan.occurrenceDigest(context.partition(), runStart);
                        episodes.add(
                            new Episode(
                                occurrence,
                                runStart,
                                startedAt,
                                current.firstId(),
                                current.time(),
                                run
                            )
                        );
                    }
                    if (run > longest) {
                        longest = run;
                        bestStart = runStart;
                        bestEnd = current.firstId();
                    }
                } else {
                    if (
                        previous.truth() == Truth.TRUE &&
                        elapsed > plan.holdUs &&
                        plan.minimumUs > 0
                    ) {
                        evidence.addAll(previous.ids());
                        evidence.addAll(current.ids());
                    }
                    run = 0;
                    runStart = null;
                }
            }
            if (current.truth() != Truth.TRUE) {
                run = 0;
                runStart = null;
            }
        }
        if (evidence.size() > 128) return failure(ScalarValue.Status.STATE_LIMIT, "EVIDENCE_LIMIT");
        if (plan.minimumUs == 0 && zero != null) return new Result(
            positive(),
            0,
            zero,
            zero,
            List.copyOf(evidence)
        );
        if (longest >= plan.minimumUs && bestStart != null) return new Result(
            positive(),
            longest,
            bestStart,
            bestEnd,
            List.copyOf(evidence)
        );
        return new Result(
            ScalarValue.unknown("WITNESS_NOT_PROVEN", List.copyOf(evidence)),
            longest,
            null,
            null,
            List.copyOf(evidence)
        );
    }

    private static Truth truth(ScalarValue value) {
        if (value.status() == ScalarValue.Status.UNKNOWN) return Truth.UNKNOWN;
        var literal = PARSER.literal(value.literal().toString());
        if (literal.path("type").asText().equals("NULL")) return Truth.UNKNOWN;
        require(literal.path("type").asText().equals("BOOLEAN"), "TYPE_MISMATCH");
        return literal.path("value").booleanValue() ? Truth.TRUE : Truth.FALSE;
    }

    private static long duration(String value) {
        try {
            require(value.matches("0|[1-9][0-9]{0,18}"), "DURATION_RANGE");
            return Long.parseLong(value);
        } catch (NumberFormatException failure) {
            throw new VclException("DURATION_RANGE", "");
        }
    }

    private static ScalarValue positive() {
        return ScalarValue.known(
            JsonNodeFactory.instance.objectNode().put("type", "BOOLEAN").put("value", true)
        );
    }

    private static Result failure(ScalarValue.Status status, String reason) {
        return new Result(ScalarValue.failure(status, reason), 0, null, null, List.of());
    }

    private static void require(boolean condition, String code) {
        if (!condition) throw new VclException(code, "");
    }
}
