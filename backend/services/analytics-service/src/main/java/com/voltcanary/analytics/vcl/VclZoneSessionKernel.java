package com.voltcanary.analytics.vcl;

import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclCompilation;
import com.voltcanary.vcl.VclDocument;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclParser;
import com.voltcanary.vcl.VclRegistry;
import com.voltcanary.vcl.VclUnits;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

/** Circle-zone session transitions over actual WGS84 observations. */
public final class VclZoneSessionKernel {
    private static final String VERSION = "vcl-zone-session-1";
    private static final VclParser PARSER = new VclParser();
    private static final VclRegistry REGISTRY = new VclRegistry(PARSER);
    private static final VclUnits UNITS = VclUnits.standard();

    public enum Phase { UNSEEN, OUTSIDE, LEFT_CENSORED, OPEN, CLOSED, UNKNOWN }

    public record State(String planDigest, KeyedStateStore.Key key, Phase phase, Instant lastAt, UUID lastId,
            Instant deadline, Wgs84ScalarKernel.Region lastRegion, Instant enteredAt, UUID entryWitness,
            Instant outsideSince, int outsideCount, UUID exitWitness, String reason) {
        public State {
            require(planDigest != null && planDigest.matches("[0-9a-f]{64}") && key != null && phase != null
                    && lastRegion != null && (lastAt == null) == (lastId == null) && (lastAt == null) == (deadline == null)
                    && (enteredAt == null) == (entryWitness == null) && outsideCount >= 0 && outsideCount <= 30
                    && (outsideCount == 0) == (outsideSince == null)
                    && (phase == Phase.CLOSED) == (exitWitness != null) && (phase == Phase.UNKNOWN) == (reason != null),
                    "SESSION_STATE_INVALID");
            require(phase != Phase.OPEN && phase != Phase.CLOSED || enteredAt != null, "SESSION_STATE_INVALID");
            require(lastAt == null || lastAt.isBefore(deadline), "SESSION_STATE_INVALID");
            require(enteredAt == null || lastAt != null && !enteredAt.isAfter(lastAt), "SESSION_STATE_INVALID");
            require(outsideSince == null || enteredAt != null && !outsideSince.isBefore(enteredAt)
                    && !outsideSince.isAfter(lastAt), "SESSION_STATE_INVALID");
        }
    }

    public record Observation(UUID id, Instant eventTime, KeyedStateStore.Key key, UUID zone,
            SpatialFeatureSnapshot.Point position, BigDecimal accuracyMetres) {
        public Observation {
            require(id != null && eventTime != null && key != null, "ZONE_INPUT_INVALID");
            VclWindowPlan.micros(eventTime);
            require(accuracyMetres == null || accuracyMetres.signum() >= 0 && accuracyMetres.precision() <= 38
                    && Math.max(accuracyMetres.scale(), 0) <= 12, "ZONE_ACCURACY_INVALID");
        }
    }

    public static Observation observation(UUID id, Instant at, KeyedStateStore.Key key,
            ScalarValue zone, ScalarValue position, ScalarValue accuracy) {
        var zoneId = known(zone) ? UUID.fromString(zone.literal().path("value").textValue()) : null;
        var point = known(position) ? position.literal().path("value") : null;
        return new Observation(id, at, key, zoneId,
                point == null ? null : new SpatialFeatureSnapshot.Point(
                        point.path("longitude").doubleValue(), point.path("latitude").doubleValue()),
                known(accuracy) ? new BigDecimal(accuracy.literal().path("value").textValue()) : null);
    }

    private static boolean known(ScalarValue value) {
        return value != null && value.status() == ScalarValue.Status.OK && !value.isNull();
    }

    public record Plan(String digest, String scope, UUID rule, SpatialFeatureSnapshot.Circle circle,
            BigDecimal uncertaintyFloor, boolean allowFloor, long maxDurationUs, long maxGapUs,
            long exitDebounceUs, int minimumExitObservations) { }

    public static Plan compile(VclCompilation compilation, String nodeId, SpatialFeatureSnapshot geometry, UUID zone) {
        require(compilation != null && compilation.kind() == VclDocument.Kind.RULE, "RULE_REQUIRED");
        require(REGISTRY.digest().equals(compilation.registryDigest())
                && UNITS.digest().equals(compilation.unitRegistryDigest()), "REGISTRY_VERSION_UNAVAILABLE");
        var document = PARSER.parse(compilation.canonicalDocument());
        require(compilation.documentDigest().equals(VclCanonical.documentDigest(document))
                && compilation.semanticDigest().equals(VclCanonical.semanticDigest(document)), "SESSION_PLAN_CHANGED");
        String scope = compilation.accessScopeDigest();
        require(geometry != null && geometry.scopeDigest().equals(scope), "ACCESS_SCOPE_CHANGED");
        var circle = geometry.circles().get(zone);
        require(circle != null, "SPATIAL_REVISION_UNAVAILABLE");
        var node = compilation.graph().stream().filter(candidate -> nodeId.equals(candidate.id())
                && "zone_session".equals(candidate.method())).findFirst()
                .orElseThrow(() -> new VclException("SESSION_OPERATOR_REQUIRED", ""));
        var authored = java.util.stream.StreamSupport.stream(document.spec().path("nodes").spliterator(), false)
                .filter(value -> nodeId.equals(value.path("id").textValue())).findFirst()
                .orElseThrow(() -> new VclException("SESSION_PLAN_CHANGED", ""));
        var config = node.config();
        require(node.method().equals(authored.path("method").textValue()) && config.equals(authored.path("config"))
                && java.util.Objects.equals(node.inputs().get("events"), authored.at("/inputs/events").textValue()),
                "SESSION_PLAN_CHANGED");
        var floor = config.path("uncertaintyFloorMetres").decimalValue();
        boolean allowFloor = "APPROVED_FLOOR".equals(config.path("accuracyFallback").textValue());
        long max = Long.parseLong(config.path("maxDurationUs").textValue());
        long gap = Long.parseLong(document.spec().at("/coverage/maxGapUs").textValue());
        long debounce = Long.parseLong(config.path("exitDebounceUs").textValue());
        int count = config.path("minimumExitObservations").intValue();
        require(max > 0 && gap > 0 && debounce >= 0 && count >= 1 && count <= 30
                && (!allowFloor || floor.signum() > 0), "ZONE_SESSION_POLICY_INVALID");
        String spatial = new SpatialFeatureSnapshot(scope, Map.of(zone, circle)).digest();
        String digest = VclCanonical.sha256(String.join("\n", VERSION, Wgs84ScalarKernel.VERSION, spatial,
                compilation.semanticDigest(), nodeId, VclCanonical.encode(config)));
        return new Plan(digest, scope, UUID.fromString(document.spec().path("ruleId").textValue()), circle,
                floor, allowFloor, max, gap, debounce, count);
    }

    public static State empty(Plan plan, KeyedStateStore.Key key) {
        require(plan.rule().equals(key.ruleId()), "SESSION_OCCURRENCE_CHANGED");
        return new State(plan.digest(), key, Phase.UNSEEN, null, null, null, Wgs84ScalarKernel.Region.UNCERTAIN,
                null, null, null, 0, null, null);
    }

    public State observe(Plan plan, State state, Observation sample, SpatialFeatureSnapshot current) {
        fence(plan, state, current.scopeDigest());
        require(state.key().equals(sample.key()), "SESSION_OCCURRENCE_CHANGED");
        if (terminal(state) || sample.position() == null) return state;
        if (!plan.circle().id().equals(sample.zone()) || !plan.circle().equals(current.circles().get(sample.zone()))) {
            return unknown(state, "ZONE_REVISION_CHANGED");
        }
        var at = sample.eventTime();
        if (state.lastAt() != null) {
            require(at.isAfter(state.lastAt()) || at.equals(state.lastAt()) && sample.id().compareTo(state.lastId()) > 0,
                    "SESSION_CORRECTION_REQUIRED");
            if (!at.isBefore(state.deadline())) return unknown(state, "SESSION_TIMEOUT");
            if (state.phase() == Phase.OPEN
                    && VclWindowPlan.micros(at) - VclWindowPlan.micros(state.lastAt()) > plan.maxGapUs()) {
                return unknown(state, "SESSION_SOURCE_GAP");
            }
        }
        var region = classify(plan, sample);
        if (at.equals(state.lastAt()) && region != state.lastRegion()) {
            region = Wgs84ScalarKernel.Region.UNCERTAIN;
        }
        if (state.phase() == Phase.OPEN) return advanceExit(plan, state, sample, region);
        boolean observedOutside = state.lastAt() != null && state.lastRegion() == Wgs84ScalarKernel.Region.OUTSIDE
                && VclWindowPlan.micros(at) - VclWindowPlan.micros(state.lastAt()) <= plan.maxGapUs();
        boolean entry = region == Wgs84ScalarKernel.Region.INSIDE && observedOutside && at.isAfter(state.lastAt());
        Phase phase = entry ? Phase.OPEN : switch (region) {
            case OUTSIDE -> Phase.OUTSIDE;
            case INSIDE -> Phase.LEFT_CENSORED;
            case UNCERTAIN -> Phase.LEFT_CENSORED;
        };
        Instant deadline = entry || state.deadline() == null ? at.plus(plan.maxDurationUs(), ChronoUnit.MICROS) : state.deadline();
        VclWindowPlan.micros(deadline);
        return new State(plan.digest(), state.key(), phase, at, sample.id(), deadline, region,
                entry ? at : null, entry ? sample.id() : null, null, 0, null, null);
    }

    private static State advanceExit(Plan plan, State state, Observation sample, Wgs84ScalarKernel.Region region) {
        var at = sample.eventTime();
        boolean outside = region == Wgs84ScalarKernel.Region.OUTSIDE;
        Instant since = outside ? state.outsideSince() == null ? at : state.outsideSince() : null;
        int count = 0;
        if (outside) {
            int additional = at.equals(state.lastAt()) ? 0 : 1;
            count = Math.min(plan.minimumExitObservations(), state.outsideCount() + additional);
        }
        boolean closed = outside && count >= plan.minimumExitObservations()
                && VclWindowPlan.micros(at) - VclWindowPlan.micros(since) >= plan.exitDebounceUs()
                && at.isAfter(state.enteredAt());
        return new State(plan.digest(), state.key(), closed ? Phase.CLOSED : Phase.OPEN, at, sample.id(),
                state.deadline(), region, state.enteredAt(), state.entryWitness(), since, count,
                closed ? sample.id() : null, null);
    }

    static Wgs84ScalarKernel.Region classify(Plan plan, Observation sample) {
        if (sample.position() == null || sample.accuracyMetres() == null && !plan.allowFloor()) {
            return Wgs84ScalarKernel.Region.UNCERTAIN;
        }
        BigDecimal accuracy = sample.accuracyMetres() == null ? plan.uncertaintyFloor()
                : sample.accuracyMetres().max(plan.uncertaintyFloor());
        return Wgs84ScalarKernel.classify(sample.position(), plan.circle(), accuracy);
    }

    public static State timeout(Plan plan, State state, Instant dueAt, String scope) {
        fence(plan, state, scope);
        if (terminal(state)) return state;
        require(state.deadline() != null && !dueAt.isBefore(state.deadline()), "SESSION_TIMER_NOT_DUE");
        return unknown(state, "SESSION_TIMEOUT");
    }

    public static State pause(Plan plan, State state, String scope) {
        fence(plan, state, scope);
        return state.phase() == Phase.UNSEEN || terminal(state) ? state : unknown(state, "PAUSED");
    }

    private static State unknown(State state, String reason) {
        return new State(state.planDigest(), state.key(), Phase.UNKNOWN, state.lastAt(), state.lastId(),
                state.deadline(), state.lastRegion(), state.enteredAt(), state.entryWitness(), null, 0, null, reason);
    }

    private static boolean terminal(State state) {
        return state.phase() == Phase.CLOSED || state.phase() == Phase.UNKNOWN;
    }

    private static void fence(Plan plan, State state, String scope) {
        require(plan.scope().equals(scope), "ACCESS_SCOPE_CHANGED");
        require(plan.digest().equals(state.planDigest()) && plan.rule().equals(state.key().ruleId()), "SESSION_PLAN_CHANGED");
    }

    private static void require(boolean valid, String code) {
        if (!valid) throw new VclException(code, "");
    }
}
