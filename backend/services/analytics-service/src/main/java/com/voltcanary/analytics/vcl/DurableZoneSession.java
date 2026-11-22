package com.voltcanary.analytics.vcl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclParser;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/** Durable circle-session state and exact timeout receipts. */
public final class DurableZoneSession {

    public static final String PROFILE = "vcl-durable-zone-session-1";
    private static final VclParser PARSER = new VclParser();
    private final VclZoneSessionKernel kernel = new VclZoneSessionKernel();
    private final VclZoneSessionKernel.Plan plan;
    private final KeyedStateStore.Key key;
    private final Supplier<SpatialFeatureSnapshot> geometry;
    private final SessionTransactions transactions;

    public record GeometryPin(UUID zone, long revision) {
        public GeometryPin {
            require(zone != null && revision > 0, "SESSION_STATE_INVALID");
        }
    }

    private record Stored(
        VclZoneSessionKernel.State state,
        Instant openedAt,
        GeometryPin geometry
    ) {}

    @FunctionalInterface
    public interface Replay {
        java.util.List<VclZoneSessionKernel.Observation> read(Instant from, Instant through);
    }

    public DurableZoneSession(
        KeyedStateStore store,
        VclZoneSessionKernel.Plan plan,
        KeyedStateStore.Key key,
        String ownerPins,
        Runnable authorize,
        DurableWindowSession.Commit commit,
        UnaryOperator<Instant> timeoutDue,
        Supplier<SpatialFeatureSnapshot> geometry
    ) {
        require(plan.rule().equals(key.ruleId()), "SESSION_OCCURRENCE_CHANGED");
        this.plan = plan;
        this.key = key;
        this.geometry = java.util.Objects.requireNonNull(geometry);
        transactions = new SessionTransactions(
            store,
            key,
            PROFILE,
            plan.digest(),
            ownerPins,
            authorize,
            commit,
            timeoutDue
        );
    }

    public String pinsDigest() {
        return transactions.pins();
    }

    public KeyedStateStore.Receipt observe(
        VclZoneSessionKernel.Observation observation,
        UUID holder
    ) {
        return observe(observation, holder, null);
    }

    public KeyedStateStore.Receipt observe(
        VclZoneSessionKernel.Observation observation,
        UUID holder,
        Replay history
    ) {
        return observe(observation, holder, history, true);
    }

    public KeyedStateStore.Receipt observe(
        VclZoneSessionKernel.Observation observation,
        UUID holder,
        Replay history,
        boolean included
    ) {
        require(key.equals(observation.key()), "SESSION_OCCURRENCE_CHANGED");
        if (!included) return transactions.observe(
            observation.id(),
            VclCanonical.sha256("SESSION_FILTER_UNKNOWN\n" + digest(observation)),
            holder,
            null,
            stored -> {
                var saved = checked(stored);
                return update(saved, saved.state(), saved.openedAt());
            }
        );
        var geometryPin = new GeometryPin(plan.circle().id(), plan.circle().revision());
        var initial = encode(new Stored(VclZoneSessionKernel.empty(plan, key), null, geometryPin));
        return transactions.observe(
            observation.id(),
            digest(observation),
            holder,
            initial,
            saved -> {
                var before = checked(saved);
                var current = geometry.get();
                var old = before.state();
                boolean late =
                    history != null &&
                    !terminal(old) &&
                    old.lastAt() != null &&
                    (observation.eventTime().isBefore(old.lastAt()) ||
                        (observation.eventTime().equals(old.lastAt()) &&
                            observation.id().compareTo(old.lastId()) < 0));
                boolean timedOut =
                    history != null &&
                    "SESSION_TIMEOUT".equals(old.reason()) &&
                    observation.eventTime().isBefore(old.deadline());
                boolean replayable =
                    observation.position() != null &&
                    (late || timedOut) &&
                    plan.circle().equals(current.circles().get(plan.circle().id()));
                var next = replayable
                    ? replay(before, observation, current, history)
                    : kernel.observe(plan, old, observation, current);
                if (timedOut && next.phase() != VclZoneSessionKernel.Phase.CLOSED) next = old;
                var openedAt =
                    before.openedAt() == null ||
                    (replayable && !timedOut && observation.eventTime().isBefore(before.openedAt()))
                        ? observation.eventTime()
                        : before.openedAt();
                return update(before, next, openedAt);
            }
        );
    }

    private VclZoneSessionKernel.State replay(
        Stored before,
        VclZoneSessionKernel.Observation trigger,
        SpatialFeatureSnapshot current,
        Replay history
    ) {
        var from = trigger.eventTime().isBefore(before.openedAt())
            ? trigger.eventTime()
            : before.openedAt();
        var through = before.state().lastAt();
        if (trigger.eventTime().isAfter(through)) through = trigger.eventTime();
        var rows = history.read(from, through);
        require(rows.size() <= VclDurationWitnessKernel.MAX_INPUTS, "SESSION_REPLAY_INPUT_LIMIT");
        var state = VclZoneSessionKernel.empty(plan, key);
        var ids = new java.util.HashSet<UUID>();
        for (var row : rows
            .stream()
            .sorted(
                java.util.Comparator.comparing(
                    VclZoneSessionKernel.Observation::eventTime
                ).thenComparing(VclZoneSessionKernel.Observation::id)
            )
            .toList()) {
            require(
                ids.add(row.id()) &&
                    !row.eventTime().isBefore(from) &&
                    !row.eventTime().isAfter(through),
                "SESSION_REPLAY_INPUT_CHANGED"
            );
            state = kernel.observe(plan, state, row, current);
        }
        require(
            ids.contains(trigger.id()) && ids.contains(before.state().lastId()),
            "SESSION_REPLAY_INPUT_CHANGED"
        );
        return state;
    }

    public KeyedStateStore.Receipt timeout(KeyedStateStore.DueTimer due, UUID holder) {
        return transactions.timeout(due, holder, (saved, dueAt) -> {
            var before = checked(saved);
            return update(
                before,
                VclZoneSessionKernel.timeout(plan, before.state(), dueAt, plan.scope()),
                before.openedAt()
            );
        });
    }

    public static GeometryPin geometryPin(KeyedStateStore.Key key, KeyedStateStore.State state) {
        return decode(key, state).geometry();
    }

    public static VclZoneSessionKernel.State sessionState(
        KeyedStateStore.Key key,
        KeyedStateStore.State state
    ) {
        return decode(key, state).state();
    }

    public static KeyedStateStore.State pauseSnapshot(
        KeyedStateStore.Key key,
        KeyedStateStore.State state
    ) {
        var before = decode(key, state);
        var old = before.state();
        require(
            old.phase() != VclZoneSessionKernel.Phase.UNSEEN && !terminal(old),
            "SESSION_STATE_INVALID"
        );
        var paused = new VclZoneSessionKernel.State(
            old.planDigest(),
            key,
            VclZoneSessionKernel.Phase.UNKNOWN,
            old.lastAt(),
            old.lastId(),
            old.deadline(),
            old.lastRegion(),
            old.enteredAt(),
            old.entryWitness(),
            null,
            0,
            null,
            "PAUSED"
        );
        return encode(new Stored(paused, before.openedAt(), before.geometry()));
    }

    private Stored checked(KeyedStateStore.State state) {
        var stored = decode(key, state);
        require(
            plan.digest().equals(stored.state().planDigest()) &&
                plan.circle().id().equals(stored.geometry().zone()) &&
                plan.circle().revision() == stored.geometry().revision(),
            "SESSION_PLAN_CHANGED"
        );
        return stored;
    }

    private static SessionTransactions.Update update(
        Stored before,
        VclZoneSessionKernel.State after,
        Instant openedAt
    ) {
        return new SessionTransactions.Update(
            encode(new Stored(after, openedAt, before.geometry())),
            openedAt,
            terminal(after) ? null : after.deadline(),
            terminal(after) && !before.state().equals(after),
            "SESSION_TIMEOUT".equals(before.state().reason()) &&
                after.phase() == VclZoneSessionKernel.Phase.CLOSED
        );
    }

    private static boolean terminal(VclZoneSessionKernel.State state) {
        return (
            state.phase() == VclZoneSessionKernel.Phase.CLOSED ||
            state.phase() == VclZoneSessionKernel.Phase.UNKNOWN
        );
    }

    private static KeyedStateStore.State encode(Stored stored) {
        var value = stored.state();
        var json = JsonNodeFactory.instance
            .objectNode()
            .put("profile", PROFILE)
            .put("planDigest", value.planDigest())
            .put("keyDigest", value.key().digest())
            .put("zone", text(stored.geometry().zone()))
            .put("zoneRevision", Long.toString(stored.geometry().revision()))
            .put("openedAt", text(stored.openedAt()))
            .put("phase", value.phase().name())
            .put("lastAt", text(value.lastAt()))
            .put("lastId", text(value.lastId()))
            .put("deadline", text(value.deadline()))
            .put("lastRegion", value.lastRegion().name())
            .put("enteredAt", text(value.enteredAt()))
            .put("entryWitness", text(value.entryWitness()))
            .put("outsideSince", text(value.outsideSince()))
            .put("outsideCount", Integer.toString(value.outsideCount()))
            .put("exitWitness", text(value.exitWitness()))
            .put("reason", value.reason());
        return KeyedStateStore.State.parse(VclCanonical.encode(json));
    }

    private static Stored decode(KeyedStateStore.Key key, KeyedStateStore.State stored) {
        try {
            var json = PARSER.readJson(stored.payload().getBytes(StandardCharsets.UTF_8));
            String count = json.path("outsideCount").textValue();
            require(
                json.size() == 17 &&
                    PROFILE.equals(json.path("profile").textValue()) &&
                    key.digest().equals(json.path("keyDigest").textValue()) &&
                    count != null &&
                    count.matches("[0-9]|[12][0-9]|30"),
                "SESSION_STATE_INVALID"
            );
            var geometry = new GeometryPin(
                uuid(json.path("zone")),
                Long.parseLong(json.path("zoneRevision").textValue())
            );
            var state = new VclZoneSessionKernel.State(
                json.path("planDigest").textValue(),
                key,
                VclZoneSessionKernel.Phase.valueOf(json.path("phase").textValue()),
                instant(json.path("lastAt")),
                uuid(json.path("lastId")),
                instant(json.path("deadline")),
                Wgs84ScalarKernel.Region.valueOf(json.path("lastRegion").textValue()),
                instant(json.path("enteredAt")),
                uuid(json.path("entryWitness")),
                instant(json.path("outsideSince")),
                Integer.parseInt(count),
                uuid(json.path("exitWitness")),
                json.path("reason").textValue()
            );
            var openedAt = instant(json.path("openedAt"));
            require(
                (openedAt == null) == (state.phase() == VclZoneSessionKernel.Phase.UNSEEN) &&
                    (state.lastAt() == null || !openedAt.isAfter(state.lastAt())),
                "SESSION_STATE_INVALID"
            );
            return new Stored(state, openedAt, geometry);
        } catch (RuntimeException invalid) {
            throw new VclException("SESSION_STATE_INVALID", "");
        }
    }

    private String digest(VclZoneSessionKernel.Observation observation) {
        var json = JsonNodeFactory.instance
            .objectNode()
            .put("id", observation.id().toString())
            .put("at", observation.eventTime().toString())
            .put("zone", text(observation.zone()))
            .put(
                "accuracyMetres",
                observation.accuracyMetres() == null
                    ? null
                    : observation.accuracyMetres().toPlainString()
            );
        if (observation.position() == null) json.putNull("position");
        else json.putObject("position")
            .put("longitude", observation.position().longitude())
            .put("latitude", observation.position().latitude());
        return VclCanonical.sha256(pinsDigest() + "\n" + VclCanonical.encode(json));
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    private static Instant instant(JsonNode value) {
        return value.isNull() ? null : Instant.parse(value.textValue());
    }

    private static UUID uuid(JsonNode value) {
        return value.isNull() ? null : UUID.fromString(value.textValue());
    }

    private static void require(boolean valid, String code) {
        if (!valid) throw new VclException(code, "");
    }
}
