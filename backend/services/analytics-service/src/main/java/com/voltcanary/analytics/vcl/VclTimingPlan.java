package com.voltcanary.analytics.vcl;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclCompilation;
import com.voltcanary.vcl.VclDocument;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclMetadata;
import com.voltcanary.vcl.VclParser;
import java.math.BigInteger;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Pure event-time arithmetic over explicitly supplied trusted progress; no coverage or durable timers. */
public final class VclTimingPlan {
    public static final String VERSION = "vcl-timing-1";
    private static final VclParser PARSER = new VclParser();
    private static final long MIN_US = VclWindowPlan.micros(Instant.parse("0001-01-01T00:00:00Z"));
    private static final long MAX_US = VclWindowPlan.micros(Instant.parse("9999-12-31T23:59:59.999999Z"));
    private final long allowedLatenessUs;
    private final long maxFutureSkewUs;
    private final long idleTimeoutUs;
    private final Set<VclCompilation.Dependency> requiredInputs;
    private final String scopeDigest;
    private final String digest;

    public enum Admission { ELIGIBLE_TIME, QUARANTINED_FUTURE }
    private record InputIdentity(VclMetadata.Resource kind, UUID id) { }

    /** Null trustedThrough is explicit unknown progress, never an idle-input exemption. */
    public record Progress(VclCompilation.Dependency input, Instant trustedThrough, Instant acceptedAt) {
        public Progress { Objects.requireNonNull(input); }
        @Override public String toString() { return "TimingProgress[]"; }
    }

    private VclTimingPlan(VclCompilation compilation) {
        require(compilation != null && compilation.kind() == VclDocument.Kind.RULE, "RULE_REQUIRED");
        var document = PARSER.parse(compilation.canonicalDocument());
        require(compilation.documentDigest().equals(VclCanonical.documentDigest(document))
                && compilation.semanticDigest().equals(VclCanonical.semanticDigest(document)), "TIMING_DOCUMENT_CHANGED");
        var timing = document.spec().path("timing");
        allowedLatenessUs = duration(timing.path("allowedLatenessUs").asText());
        maxFutureSkewUs = duration(timing.path("maxFutureSkewUs").asText());
        idleTimeoutUs = duration(timing.path("idleTimeoutUs").asText());
        require(idleTimeoutUs > 0, "DURATION_NOT_POSITIVE");
        // Absence is not allowed its own second grace period.
        for (var node : compilation.graph()) {
            if (node.method().equals("absent_within")) {
                require(duration(node.config().path("allowedLatenessUs").asText()) == allowedLatenessUs, "LATENESS_MISMATCH");
            }
        }
        scopeDigest = compilation.accessScopeDigest();
        require(scopeDigest != null && scopeDigest.matches("[0-9a-f]{64}"), "ACCESS_SCOPE_REQUIRED");
        var inputs = new HashSet<VclCompilation.Dependency>();
        var identities = new HashSet<InputIdentity>();
        for (var dependency : compilation.dependencies()) {
            if (dependency.kind() == VclMetadata.Resource.BINDING || dependency.kind() == VclMetadata.Resource.DATASET) {
                require(dependency.id() != null && dependency.revision() > 0 && dependency.snapshotDigest() != null
                        && dependency.snapshotDigest().matches("[0-9a-f]{64}"), "TIMING_INPUT_INVALID");
                require(identities.add(new InputIdentity(dependency.kind(), dependency.id())), "TIMING_INPUT_DUPLICATE");
                inputs.add(dependency);
            }
        }
        require(!inputs.isEmpty() && inputs.size() <= 128, "TIMING_INPUT_REQUIRED");
        requiredInputs = Set.copyOf(inputs);
        String pins = requiredInputs.stream().map(Object::toString).sorted().collect(java.util.stream.Collectors.joining("\n"));
        digest = VclCanonical.sha256(VERSION + "\n" + compilation.semanticDigest() + "\n" + scopeDigest + "\n" + pins);
    }

    public static VclTimingPlan compile(VclCompilation compilation) { return new VclTimingPlan(compilation); }
    public String digest() { return digest; }
    public Set<VclCompilation.Dependency> requiredInputs() { return requiredInputs; }

    public Instant windowCloseDueAt(Instant end) {
        var due = end.plus(allowedLatenessUs, java.time.temporal.ChronoUnit.MICROS);
        VclWindowPlan.micros(due);
        return due;
    }

    /** Accepted time is a trusted owner timestamp, never a user-provided clock or the process wall clock. */
    public Admission classify(Instant occurredAt, Instant acceptedAt) {
        long occurred = VclWindowPlan.micros(occurredAt);
        long accepted = VclWindowPlan.micros(acceptedAt);
        return BigInteger.valueOf(occurred).subtract(BigInteger.valueOf(accepted)).compareTo(BigInteger.valueOf(maxFutureSkewUs)) > 0
                ? Admission.QUARANTINED_FUTURE : Admission.ELIGIBLE_TIME;
    }

    /** Minimum trusted progress minus root lateness once; this time frontier is not a coverage proof. */
    public ScalarValue watermark(List<Progress> progress, String expectedDigest, String authorizedScopeDigest) {
        if (!scopeDigest.equals(authorizedScopeDigest)) return failure(ScalarValue.Status.ACCESS_REVOKED, "ACCESS_SCOPE_CHANGED");
        if (!digest.equals(expectedDigest)) return failure(ScalarValue.Status.SCHEMA_UNAVAILABLE, "TIMING_PLAN_CHANGED");
        if (progress == null) return failure(ScalarValue.Status.INVALID_INPUT, "TIMING_PROGRESS_REQUIRED");
        if (progress.size() > 128) return failure(ScalarValue.Status.STATE_LIMIT, "TIMING_INPUT_LIMIT");
        var seen = new HashSet<VclCompilation.Dependency>();
        long minimum = MAX_US;
        boolean unknown = false;
        try {
            for (var input : progress) {
                require(input != null, "TIMING_PROGRESS_REQUIRED");
                require(requiredInputs.contains(input.input()), "TIMING_INPUT_CHANGED");
                require(seen.add(input.input()), "TIMING_INPUT_DUPLICATE");
                // Unknown projections still participate; idleness cannot advance the remaining inputs.
                if (input.trustedThrough() == null) {
                    require(input.acceptedAt() == null, "TIMING_PROGRESS_INVALID");
                    unknown = true;
                    continue;
                }
                if (classify(input.trustedThrough(), input.acceptedAt()) == Admission.QUARANTINED_FUTURE) {
                    return failure(ScalarValue.Status.INVALID_INPUT, "FUTURE_PROGRESS_QUARANTINED");
                }
                minimum = Math.min(minimum, VclWindowPlan.micros(input.trustedThrough()));
            }
            require(seen.equals(requiredInputs), "TIMING_INPUTS_MISSING");
        } catch (VclException error) {
            return failure(ScalarValue.Status.INVALID_INPUT, error.code());
        }
        if (unknown) return ScalarValue.unknown("TIMING_PROGRESS_UNKNOWN", List.of());
        var frontier = BigInteger.valueOf(minimum).subtract(BigInteger.valueOf(allowedLatenessUs));
        if (frontier.compareTo(BigInteger.valueOf(MIN_US)) < 0) return ScalarValue.unknown("TIMING_BEFORE_RANGE", List.of());
        long micros = frontier.longValueExact();
        var instant = Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L), Math.floorMod(micros, 1_000_000L) * 1000);
        return ScalarValue.known(JsonNodeFactory.instance.objectNode().put("type", "INSTANT").put("value", instant.toString()));
    }

    /** Expiration requests an UNKNOWN wait outcome; it is never proof of an observed exit or absence. */
    public boolean idleExpired(Instant lastAcceptedAt, Instant checkedAt) {
        long previous = VclWindowPlan.micros(lastAcceptedAt);
        long current = VclWindowPlan.micros(checkedAt);
        require(current >= previous, "TIMING_CLOCK_REGRESSED");
        return BigInteger.valueOf(current).subtract(BigInteger.valueOf(previous)).compareTo(BigInteger.valueOf(idleTimeoutUs)) >= 0;
    }

    private static long duration(String value) {
        require(value.matches("0|[1-9][0-9]{0,18}"), "DURATION_RANGE");
        try { return Long.parseLong(value); }
        catch (NumberFormatException error) { throw new VclException("DURATION_RANGE", ""); }
    }
    private static ScalarValue failure(ScalarValue.Status status, String reason) { return ScalarValue.failure(status, reason); }
    private static void require(boolean condition, String code) { if (!condition) throw new VclException(code, ""); }
    @Override public String toString() { return "VclTimingPlan[version=" + VERSION + "]"; }
}
