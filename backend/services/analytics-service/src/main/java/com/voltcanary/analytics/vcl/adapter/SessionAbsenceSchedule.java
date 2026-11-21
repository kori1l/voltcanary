package com.voltcanary.analytics.vcl.adapter;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.voltcanary.analytics.vcl.DurableWindowSession;
import com.voltcanary.analytics.vcl.DurableZoneSession;
import com.voltcanary.analytics.vcl.KeyedStateStore;
import com.voltcanary.analytics.vcl.VclTimingPlan;
import com.voltcanary.analytics.vcl.VclWindowPlan;
import com.voltcanary.analytics.vcl.VclWindowSessionKernel;
import com.voltcanary.analytics.vcl.VclZoneSessionKernel;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclObservationReadiness;
import com.voltcanary.vcl.VclParser;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Persists the exact journal cut alongside the wait for session coverage. */
final class SessionAbsenceSchedule {
    private static final String PROFILE = "vcl-session-absence-wait-1";
    private static final VclParser PARSER = new VclParser();

    static final String MEASURE = "vcl-session-absence-measure-1";
    record Recipe(String node, String checkpoint, Instant openedAt, String trigger, UUID previous, Instant through) { }

    static UUID id(String pins, String purpose) {
        return UUID.nameUUIDFromBytes((PROFILE + "\n" + pins + "\n" + purpose).getBytes(StandardCharsets.UTF_8));
    }

    static KeyedStateStore.Transition schedule(JdbcKeyedStateStore store, KeyedStateStore.Key key, KeyedStateStore.Lease lease,
            KeyedStateStore.Transition transition, String node, String checkpoint, Instant openedAt,
            Instant observedAt, boolean zone, VclTimingPlan timing) {
        if (node == null) return transition;
        var existing = lease.timers().stream()
                .filter(timer -> timer.timer().id().equals(id(lease.pinsDigest(), "wait"))).findFirst();
        var window = window(key, transition.next(), zone);
        if (existing.isPresent()) {
            if (!lease.snapshot().state().equals(transition.next()) || !transition.timers().isEmpty()) {
                throw new VclException("SESSION_CORRECTION_REQUIRED", "");
            }
            var scheduled = existing.get();
            var pending = recipe(new KeyedStateStore.DueTimer(key, lease.pinsDigest(), scheduled), transition.next());
            if (!observedAt.isAfter(pending.through() == null ? window.endExclusive() : pending.through())
                    && VclObservationReadiness.checkpoint(checkpoint) > VclObservationReadiness.checkpoint(pending.trigger())) {
                pending = new Recipe(pending.node(), pending.checkpoint(), pending.openedAt(), checkpoint,
                        pending.previous(), pending.through());
                return withTimer(transition, timer(lease, transition, scheduled.timer().dueAt(), pending));
            }
            return withTimer(transition, scheduled.timer());
        }
        if (window == null) return transition;
        var dueAt = timing.windowCloseDueAt(window.endExclusive());
        if (!transition.outputs().isEmpty()) {
            return withTimer(transition, timer(lease, transition, dueAt, new Recipe(node, checkpoint, openedAt, checkpoint, null, null)));
        }
        var previous = store.latestSessionMeasure(key, lease).orElse(null);
        if (previous == null) return transition;
        var measure = PARSER.readJson(previous.output().payload().payload().getBytes(StandardCharsets.UTF_8));
        if (!MEASURE.equals(measure.path("profile").textValue()) || !node.equals(measure.path("node").textValue())
                || !measure.path("closingCheckpoint").isTextual()) return transition;
        var end = measure.path("sessionEnd").isTextual() ? Instant.parse(measure.path("sessionEnd").textValue()) : window.endExclusive();
        if (end.isBefore(window.endExclusive())) end = window.endExclusive();
        if (observedAt.isAfter(end) || VclObservationReadiness.checkpoint(checkpoint)
                <= VclObservationReadiness.checkpoint(measure.path("checkpoint").textValue())) return transition;
        var correction = new Recipe(node, measure.path("closingCheckpoint").textValue(), openedAt, checkpoint,
                previous.output().id(), end);
        return withTimer(transition, timer(lease, transition, dueAt, correction));
    }

    private static KeyedStateStore.Timer timer(KeyedStateStore.Lease lease, KeyedStateStore.Transition transition,
            Instant dueAt, Recipe recipe) {
        var payload = JsonNodeFactory.instance.objectNode().put("profile", PROFILE).put("node", recipe.node())
                .put("checkpoint", recipe.checkpoint()).put("openedAt", recipe.openedAt().toString())
                .put("trigger", recipe.trigger()).put("previous", recipe.previous() == null ? null : recipe.previous().toString())
                .put("through", recipe.through() == null ? null : recipe.through().toString())
                .put("stateDigest", transition.next().digest());
        return new KeyedStateStore.Timer(id(lease.pinsDigest(), "wait"), KeyedStateStore.TimerKind.WAIT_TIMEOUT,
                dueAt, KeyedStateStore.State.parse(VclCanonical.encode(payload)));
    }

    private static KeyedStateStore.Transition withTimer(KeyedStateStore.Transition transition, KeyedStateStore.Timer timer) {
        return new KeyedStateStore.Transition(transition.inputId(), transition.inputRevision(), transition.inputDigest(),
                transition.expectedRevision(), transition.next(), List.of(timer), transition.consumedTimer(), transition.outputs());
    }

    static Recipe recipe(KeyedStateStore.DueTimer due, KeyedStateStore.State state) {
        var timer = due.scheduled().timer();
        var json = PARSER.readJson(timer.payload().payload().getBytes(StandardCharsets.UTF_8));
        if (timer.kind() != KeyedStateStore.TimerKind.WAIT_TIMEOUT || !timer.id().equals(id(due.pinsDigest(), "wait"))
                || (json.size() != 5 && json.size() != 8) || !PROFILE.equals(json.path("profile").textValue())
                || !state.digest().equals(json.path("stateDigest").textValue())
                || !json.path("node").isTextual() || VclObservationReadiness.checkpoint(json.path("checkpoint").textValue()) < 1) {
            throw new VclException("SESSION_WAIT_CHANGED", "");
        }
        String checkpoint = json.path("checkpoint").textValue();
        String trigger = json.size() == 5 ? checkpoint : json.path("trigger").textValue();
        if (VclObservationReadiness.checkpoint(trigger) < VclObservationReadiness.checkpoint(checkpoint)) {
            throw new VclException("SESSION_WAIT_CHANGED", "");
        }
        return new Recipe(json.path("node").textValue(), checkpoint, Instant.parse(json.path("openedAt").textValue()), trigger,
                json.path("previous").isTextual() ? UUID.fromString(json.path("previous").textValue()) : null,
                json.path("through").isTextual() ? Instant.parse(json.path("through").textValue()) : null);
    }

    static VclWindowPlan.Window window(KeyedStateStore.Key key, KeyedStateStore.State state, boolean zone) {
        if (zone) {
            var session = DurableZoneSession.sessionState(key, state);
            return session.phase() == VclZoneSessionKernel.Phase.CLOSED
                    ? new VclWindowPlan.Window(session.enteredAt(), session.lastAt()) : null;
        }
        var session = DurableWindowSession.decode(key, state);
        return session.phase() == VclWindowSessionKernel.Phase.CLOSED
                ? new VclWindowPlan.Window(session.firstAt(), session.lastAt()) : null;
    }

    private SessionAbsenceSchedule() { }
}
