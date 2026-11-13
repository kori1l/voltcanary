package com.voltcanary.analytics.vcl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclParser;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/** Commits typed session transitions and timer receipts together in the keyed store. */
public final class DurableWindowSession {
    private static final String PROFILE = "vcl-durable-window-session-1";
    private static final VclParser PARSER = new VclParser();
    private final VclWindowSessionKernel kernel = new VclWindowSessionKernel();
    private final VclWindowSessionKernel.Plan plan;
    private final KeyedStateStore.Key key;
    private final SessionTransactions transactions;

    @FunctionalInterface
    public interface Commit {
        KeyedStateStore.Receipt apply(KeyedStateStore.Key key, KeyedStateStore.Lease lease,
                KeyedStateStore.Transition transition, Instant openedAt);
    }

    @FunctionalInterface
    public interface Replay {
        java.util.List<VclWindowSessionKernel.Observation> read(Instant from, Instant through);
    }

    public DurableWindowSession(KeyedStateStore store, VclWindowSessionKernel.Plan plan, KeyedStateStore.Key key,
            String ownerPinsDigest, Runnable authorize) {
        this(store, plan, key, ownerPinsDigest, authorize, (target, lease, transition, openedAt) ->
                store.commit(target, lease, transition), java.util.function.UnaryOperator.identity());
    }

    public DurableWindowSession(KeyedStateStore store, VclWindowSessionKernel.Plan plan, KeyedStateStore.Key key,
            String ownerPinsDigest, Runnable authorize, Commit commit, java.util.function.UnaryOperator<Instant> timeoutDue) {
        require(plan.rule().equals(key.ruleId()), "SESSION_OCCURRENCE_CHANGED");
        this.plan = plan;
        this.key = key;
        transactions = new SessionTransactions(store, key, PROFILE, plan.digest(), ownerPinsDigest, authorize, commit, timeoutDue);
    }

    public String pinsDigest() { return transactions.pins(); }

    public KeyedStateStore.Receipt observe(VclWindowSessionKernel.Observation observation, UUID holder) {
        return observe(observation, holder, null);
    }

    public KeyedStateStore.Receipt observe(VclWindowSessionKernel.Observation observation, UUID holder, Replay history) {
        return observe(observation, holder, history, true);
    }

    public KeyedStateStore.Receipt observe(VclWindowSessionKernel.Observation observation, UUID holder, Replay history, boolean included) {
        if (!included) return transactions.observe(observation.id(), VclCanonical.sha256("SESSION_FILTER_UNKNOWN\n" + digest(observation)),
                holder, null, stored -> {
                    var state = decode(stored);
                    return update(state, state);
                });
        return transactions.observe(observation.id(), digest(observation), holder, encode(VclWindowSessionKernel.empty(plan, key)),
                stored -> {
                    var before = decode(stored);
                    // The state stores the last time but not its ID, so a tie also needs canonical replay.
                    boolean reorder = history != null && before.lastAt() != null && !observation.eventTime().isAfter(before.lastAt())
                            && (before.phase() == VclWindowSessionKernel.Phase.OPEN
                                    || before.phase() == VclWindowSessionKernel.Phase.LEFT_CENSORED);
                    boolean timedOut = history != null && "SESSION_TIMEOUT".equals(before.reason())
                            && observation.eventTime().isBefore(before.deadline());
                    var after = reorder || timedOut ? replay(before, observation, history)
                            : kernel.observe(plan, before, observation, plan.scope());
                    if (timedOut && after.phase() != VclWindowSessionKernel.Phase.CLOSED) after = before;
                    return update(before, after);
                });
    }

    private VclWindowSessionKernel.State replay(VclWindowSessionKernel.State before,
            VclWindowSessionKernel.Observation observation, Replay history) {
        var from = before.firstAt().minus(plan.maxDurationUs(), java.time.temporal.ChronoUnit.MICROS);
        if (observation.eventTime().isBefore(from)) from = observation.eventTime();
        var through = observation.eventTime().isAfter(before.lastAt()) ? observation.eventTime() : before.lastAt();
        var rows = history.read(from, through);
        require(rows.size() <= VclDurationWitnessKernel.MAX_INPUTS, "SESSION_REPLAY_INPUT_LIMIT");
        var state = VclWindowSessionKernel.empty(plan, key);
        var seen = new java.util.HashSet<UUID>();
        boolean reachedHead = false;
        for (var row : rows.stream().sorted(java.util.Comparator.comparing(VclWindowSessionKernel.Observation::eventTime)
                .thenComparing(VclWindowSessionKernel.Observation::id)).toList()) {
            require(seen.add(row.id()) && !row.eventTime().isBefore(from) && !row.eventTime().isAfter(through),
                    "SESSION_REPLAY_INPUT_CHANGED");
            reachedHead |= row.eventTime().equals(before.lastAt());
            state = kernel.observe(plan, state, row, plan.scope());
        }
        require(seen.contains(observation.id()) && reachedHead
                && (before.startWitness() == null || seen.contains(before.startWitness())), "SESSION_REPLAY_INPUT_CHANGED");
        return state;
    }

    public KeyedStateStore.Receipt timeout(KeyedStateStore.DueTimer due, UUID holder) {
        return transactions.timeout(due, holder, (stored, dueAt) -> {
            var before = decode(stored);
            return update(before, VclWindowSessionKernel.timeout(plan, before, dueAt, plan.scope()));
        });
    }

    private static SessionTransactions.Update update(VclWindowSessionKernel.State before, VclWindowSessionKernel.State after) {
        boolean terminal = after.phase() == VclWindowSessionKernel.Phase.CLOSED || after.phase() == VclWindowSessionKernel.Phase.UNKNOWN;
        return new SessionTransactions.Update(encode(after), before.firstAt() == null ? after.firstAt() : before.firstAt(),
                terminal ? null : after.deadline(), terminal && !before.equals(after),
                "SESSION_TIMEOUT".equals(before.reason()) && after.phase() == VclWindowSessionKernel.Phase.CLOSED);
    }

    private static KeyedStateStore.State encode(VclWindowSessionKernel.State state) {
        var json = JsonNodeFactory.instance.objectNode().put("profile", PROFILE)
                .put("planDigest", state.planDigest()).put("keyDigest", state.key().digest()).put("phase", state.phase().name())
                .put("firstAt", text(state.firstAt())).put("lastAt", text(state.lastAt())).put("deadline", text(state.deadline()))
                .put("startWitness", text(state.startWitness())).put("closeWitness", text(state.closeWitness()))
                .put("reason", state.reason());
        return KeyedStateStore.State.parse(VclCanonical.encode(json));
    }

    private VclWindowSessionKernel.State decode(KeyedStateStore.State value) {
        var decoded = decode(key, value);
        require(plan.digest().equals(decoded.planDigest()), "SESSION_STATE_INVALID");
        return decoded;
    }

    /** Used by the local pause sweep while it holds the exact paused deployment and keyed row. */
    public static KeyedStateStore.State pauseSnapshot(KeyedStateStore.Key key, KeyedStateStore.State stored) {
        var state = decode(key, stored);
        require(state.phase() == VclWindowSessionKernel.Phase.OPEN
                || state.phase() == VclWindowSessionKernel.Phase.LEFT_CENSORED, "SESSION_STATE_INVALID");
        return encode(new VclWindowSessionKernel.State(state.planDigest(), key, VclWindowSessionKernel.Phase.UNKNOWN,
                state.firstAt(), state.lastAt(), state.deadline(), state.startWitness(), null, "PAUSED"));
    }

    public static VclWindowSessionKernel.State decode(KeyedStateStore.Key key, KeyedStateStore.State value) {
        try {
            var json = PARSER.readJson(value.payload().getBytes(StandardCharsets.UTF_8));
            require(json.size() == 10 && PROFILE.equals(json.path("profile").textValue())
                    && key.digest().equals(json.path("keyDigest").textValue()), "SESSION_STATE_INVALID");
            return new VclWindowSessionKernel.State(json.path("planDigest").textValue(), key,
                    VclWindowSessionKernel.Phase.valueOf(json.path("phase").textValue()),
                    instant(json.path("firstAt")), instant(json.path("lastAt")), instant(json.path("deadline")),
                    uuid(json.path("startWitness")), uuid(json.path("closeWitness")), json.path("reason").textValue());
        } catch (RuntimeException invalid) {
            throw new VclException("SESSION_STATE_INVALID", "");
        }
    }

    private String digest(VclWindowSessionKernel.Observation observation) {
        var json = JsonNodeFactory.instance.objectNode().put("id", observation.id().toString()).put("at", observation.eventTime().toString());
        var fields = json.putObject("fields");
        observation.fields().forEach((id, value) -> {
            var field = fields.putObject(id.toString()).put("status", value.status().name()).put("reason", value.reason());
            field.set("literal", value.literal());
            var evidence = field.putArray("evidence");
            value.evidence().forEach(evidenceId -> evidence.add(evidenceId.toString()));
        });
        return VclCanonical.sha256(pinsDigest() + "\n" + VclCanonical.encode(json));
    }

    private static String text(Object value) { return value == null ? null : value.toString(); }
    private static Instant instant(JsonNode value) { return value.isNull() ? null : Instant.parse(value.textValue()); }
    private static UUID uuid(JsonNode value) { return value.isNull() ? null : UUID.fromString(value.textValue()); }

    private static void require(boolean valid, String code) {
        if (!valid) throw new VclException(code, "");
    }
}
