package com.voltcanary.analytics.vcl;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclParser;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;

/** Arithmetic over an already scoped/partitioned batch; no completeness, finalization or effect authority. */
public final class VclIntervalUnionKernel {

    public static final String VERSION = "vcl-interval-union-1";
    public static final int MAX_INPUTS = 4096;
    private static final VclParser PARSER = new VclParser();
    private static final long MAX_WINDOW_US = 32L * 86_400_000_000L;

    /** Predicates have already been checked/evaluated by the shared scalar compiler/evaluator. */
    public record Input(
        UUID observationId,
        ScalarValue interval,
        ScalarValue included,
        ScalarValue excluded
    ) {
        public Input {
            Objects.requireNonNull(observationId);
            Objects.requireNonNull(interval);
            Objects.requireNonNull(included);
            Objects.requireNonNull(excluded);
        }

        @Override
        public String toString() {
            return "IntervalUnionInput[]";
        }
    }

    public record Context(String kernelVersion, String scopeDigest, VclWindowPlan.Window window) {
        public Context {
            if (
                kernelVersion == null || !kernelVersion.matches("[A-Za-z0-9._/-]{1,128}")
            ) throw new IllegalArgumentException("Invalid interval kernel version");
            if (
                scopeDigest == null || !scopeDigest.matches("[0-9a-f]{64}")
            ) throw new IllegalArgumentException("Invalid interval scope");
            Objects.requireNonNull(window);
            if (
                micros(window.startInclusive(), window.endExclusive()) > MAX_WINDOW_US
            ) throw new IllegalArgumentException("Interval window limit");
        }

        @Override
        public String toString() {
            return "IntervalUnionContext[kernelVersion=" + kernelVersion + "]";
        }
    }

    private record Span(Instant start, Instant end) {}

    private enum Truth {
        TRUE,
        FALSE,
        UNKNOWN,
    }

    public ScalarValue evaluate(Context context, List<Input> inputs, String authorizedScopeDigest) {
        if (!context.scopeDigest().equals(authorizedScopeDigest)) return failure(
            ScalarValue.Status.ACCESS_REVOKED,
            "ACCESS_SCOPE_CHANGED"
        );
        if (!VERSION.equals(context.kernelVersion())) return failure(
            ScalarValue.Status.SCHEMA_UNAVAILABLE,
            "INTERVAL_KERNEL_UNAVAILABLE"
        );
        if (inputs == null) return failure(
            ScalarValue.Status.INVALID_INPUT,
            "INTERVAL_INPUT_REQUIRED"
        );
        if (inputs.size() > MAX_INPUTS) return failure(
            ScalarValue.Status.STATE_LIMIT,
            "INTERVAL_INPUT_LIMIT"
        );
        var snapshot = new ArrayList<Input>(inputs.size());
        var ids = new HashSet<UUID>();
        for (var input : inputs) {
            if (input == null) return failure(
                ScalarValue.Status.INVALID_INPUT,
                "INTERVAL_INPUT_REQUIRED"
            );
            if (!ids.add(input.observationId())) return failure(
                ScalarValue.Status.INVALID_INPUT,
                "INTERVAL_INPUT_DUPLICATE"
            );
            snapshot.add(input);
        }
        snapshot.sort(Comparator.comparing(input -> input.observationId().toString()));
        // Infrastructure errors remain errors even when another predicate is false or unknown.
        for (var input : snapshot) {
            for (var value : List.of(input.interval(), input.included(), input.excluded())) {
                if (value.isFailure()) return value;
            }
        }
        var included = new ArrayList<Span>();
        var excluded = new ArrayList<Span>();
        var evidence = new TreeSet<UUID>(Comparator.comparing(UUID::toString));
        boolean unknown = false;
        try {
            for (var input : snapshot) {
                Truth include = truth(input.included());
                Truth exclude = truth(input.excluded());
                // Validate known interval values even if neither predicate selects this row.
                Span span = interval(input.interval());
                if (include == Truth.FALSE && exclude == Truth.FALSE) continue;
                boolean unresolved =
                    include == Truth.UNKNOWN || exclude == Truth.UNKNOWN || span == null;
                if (unresolved) {
                    unknown = true;
                    evidence.add(input.observationId());
                    for (var value : List.of(input.interval(), input.included(), input.excluded()))
                        evidence.addAll(value.evidence());
                    if (evidence.size() > 128) return failure(
                        ScalarValue.Status.STATE_LIMIT,
                        "EVIDENCE_LIMIT"
                    );
                    continue;
                }
                Span clipped = clip(span, context.window());
                if (clipped == null) continue;
                if (include == Truth.TRUE) included.add(clipped);
                if (exclude == Truth.TRUE) excluded.add(clipped);
            }
        } catch (VclException error) {
            return failure(ScalarValue.Status.INVALID_INPUT, error.code());
        }
        if (unknown) return ScalarValue.unknown("INTERVAL_INPUT_UNKNOWN", List.copyOf(evidence));
        var work = union(included);
        var breaks = union(excluded);
        long durationUs = 0;
        int firstBreak = 0;
        for (var span : work) {
            durationUs += micros(span.start(), span.end());
            while (
                    firstBreak < breaks.size() &&
                    !breaks.get(firstBreak).end().isAfter(span.start())
                )
                firstBreak++;
            for (
                int i = firstBreak;
                i < breaks.size() && breaks.get(i).start().isBefore(span.end());
                i++
            ) {
                var pause = breaks.get(i);
                Instant start = later(span.start(), pause.start());
                Instant end = earlier(span.end(), pause.end());
                if (start.isBefore(end)) durationUs -= micros(start, end);
            }
        }
        return ScalarValue.known(
            JsonNodeFactory.instance
                .objectNode()
                .put("type", "DURATION")
                .put("value", Long.toString(durationUs))
        );
    }

    private static Truth truth(ScalarValue value) {
        if (value.status() == ScalarValue.Status.UNKNOWN) return Truth.UNKNOWN;
        var literal = PARSER.literal(value.literal().toString());
        if (literal.path("type").asText().equals("NULL")) return Truth.UNKNOWN;
        if (!literal.path("type").asText().equals("BOOLEAN")) throw new VclException(
            "TYPE_MISMATCH",
            ""
        );
        return literal.path("value").booleanValue() ? Truth.TRUE : Truth.FALSE;
    }

    /** null means unknown, explicit null or open end; none is a finite observed interval. */
    private static Span interval(ScalarValue value) {
        if (value.status() == ScalarValue.Status.UNKNOWN) return null;
        var literal = PARSER.literal(value.literal().toString());
        if (literal.path("type").asText().equals("NULL")) return null;
        if (!literal.path("type").asText().equals("INTERVAL")) throw new VclException(
            "TYPE_MISMATCH",
            ""
        );
        var bounds = literal.path("value");
        Instant start = Instant.parse(bounds.path("start").asText());
        VclWindowPlan.micros(start);
        if (bounds.path("end").isNull()) return null;
        Instant end = Instant.parse(bounds.path("end").asText());
        // Enforce the same UTC bounds as window assignment after closed-literal validation.
        VclWindowPlan.micros(end);
        return new Span(start, end);
    }

    private static Span clip(Span span, VclWindowPlan.Window window) {
        Instant start = later(span.start(), window.startInclusive());
        Instant end = earlier(span.end(), window.endExclusive());
        return start.isBefore(end) ? new Span(start, end) : null;
    }

    private static List<Span> union(List<Span> input) {
        input.sort(Comparator.comparing(Span::start).thenComparing(Span::end));
        var result = new ArrayList<Span>();
        for (var span : input) {
            if (result.isEmpty() || result.getLast().end().isBefore(span.start())) result.add(span);
            else {
                var previous = result.removeLast();
                result.add(new Span(previous.start(), later(previous.end(), span.end())));
            }
        }
        return result;
    }

    private static long micros(Instant start, Instant end) {
        var duration = Duration.between(start, end);
        return duration.getSeconds() * 1_000_000L + duration.getNano() / 1000;
    }

    private static Instant later(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }

    private static Instant earlier(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }

    private static ScalarValue failure(ScalarValue.Status status, String reason) {
        return ScalarValue.failure(status, reason);
    }
}
