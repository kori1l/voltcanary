package com.voltcanary.analytics.vcl.adapter;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.voltcanary.analytics.vcl.KeyedStateStore;
import com.voltcanary.analytics.vcl.VclWindowPlan;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclObservationReadiness;
import com.voltcanary.vcl.VclParser;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

final class IntervalWindowSchedule {
    private static final String PROFILE = "vcl-interval-window-close-1";
    private static final VclParser PARSER = new VclParser();
    private static final int PREFIX_STEPS = 16;

    record Route(UUID binding, UUID eventId, String eventDigest, Runnable reauthorize) {
        static Route from(UUID binding, com.fasterxml.jackson.databind.JsonNode event, Runnable reauthorize) {
            return new Route(binding, UUID.fromString(event.path("inputEventId").textValue()),
                    VclCanonical.sha256(VclCanonical.encode(event)), reauthorize);
        }
    }

    record Recipe(String node, String planDigest, UUID generation, VclWindowPlan.Window window, Instant dueAt,
            String subjectLiteral, int prefixIndex, UUID earlyMeasure, long minimumCheckpoint, UUID previousFinal) {
        Recipe {
            if (prefixIndex < 0 || prefixIndex >= PREFIX_STEPS || minimumCheckpoint < 0
                    || (earlyMeasure != null || previousFinal != null) && prefixIndex != 0) {
                throw new VclException("INTERVAL_SCHEDULE_CHANGED", "");
            }
        }
        KeyedStateStore.State state() {
            var value = JsonNodeFactory.instance.objectNode().put("profile", PROFILE).put("node", node)
                    .put("plan", planDigest).put("generation", generation.toString())
                    .put("start", window.startInclusive().toString()).put("end", window.endExclusive().toString())
                    .put("dueAt", dueAt.toString());
            if (subjectLiteral != null) value.put("subject", subjectLiteral);
            if (prefixIndex != 0) value.put("prefix", prefixIndex);
            if (earlyMeasure != null) value.put("earlyMeasure", earlyMeasure.toString());
            if (minimumCheckpoint != 0) value.put("minimumCheckpoint", Long.toString(minimumCheckpoint));
            if (previousFinal != null) value.put("previousFinal", previousFinal.toString());
            return KeyedStateStore.State.parse(VclCanonical.encode(value));
        }

        Instant measuredThrough() {
            if (prefixIndex == 0) return window.endExclusive();
            long micros = java.time.temporal.ChronoUnit.MICROS.between(window.startInclusive(), window.endExclusive());
            return window.startInclusive().plus(micros * prefixIndex / PREFIX_STEPS, java.time.temporal.ChronoUnit.MICROS);
        }

        Recipe next(UUID matchedMeasure) {
            int index = matchedMeasure != null || prefixIndex + 1 >= PREFIX_STEPS ? 0 : prefixIndex + 1;
            return new Recipe(node, planDigest, generation, window, dueAt, subjectLiteral, index, matchedMeasure,
                    minimumCheckpoint, previousFinal);
        }

        Recipe advance(long checkpoint, UUID previous) {
            return new Recipe(node, planDigest, generation, window, dueAt, subjectLiteral,
                    previous == null ? prefixIndex : 0, earlyMeasure, checkpoint, previous);
        }
    }

    static boolean handles(KeyedStateStore.State state) {
        return PROFILE.equals(PARSER.readJson(state.payload().getBytes(StandardCharsets.UTF_8)).path("profile").textValue());
    }

    static boolean terminalMeasure(com.fasterxml.jackson.databind.JsonNode result) {
        return "vcl-interval-final-measure-1".equals(result.path("profile").textValue())
                && (result.path("finalized").booleanValue()
                        || "WINDOW_SOURCE_IDLE".equals(result.path("reason").textValue()));
    }

    static Recipe recipe(KeyedStateStore.State state) {
        var value = PARSER.readJson(state.payload().getBytes(StandardCharsets.UTF_8));
        if (!PROFILE.equals(value.path("profile").textValue()) || value.size() != 7 + (value.has("subject") ? 1 : 0)
                + (value.has("prefix") ? 1 : 0) + (value.has("earlyMeasure") ? 1 : 0)
                + (value.has("minimumCheckpoint") ? 1 : 0) + (value.has("previousFinal") ? 1 : 0)
                || (value.has("prefix") && !value.path("prefix").isIntegralNumber())) {
            throw new VclException("INTERVAL_SCHEDULE_CHANGED", "");
        }
        return new Recipe(value.path("node").textValue(), value.path("plan").textValue(),
                UUID.fromString(value.path("generation").textValue()),
                new VclWindowPlan.Window(Instant.parse(value.path("start").textValue()), Instant.parse(value.path("end").textValue())),
                Instant.parse(value.path("dueAt").textValue()), value.path("subject").textValue(), value.path("prefix").asInt(0),
                value.has("earlyMeasure") ? UUID.fromString(value.path("earlyMeasure").textValue()) : null,
                value.has("minimumCheckpoint") ? VclObservationReadiness.checkpoint(value.path("minimumCheckpoint").textValue()) : 0,
                value.has("previousFinal") ? UUID.fromString(value.path("previousFinal").textValue()) : null);
    }

    static String occurrence(VclWindowPlan.Window window) {
        return VclCanonical.sha256(String.join("\n", "RULE_WINDOW_OCCURRENCE/1",
                window.startInclusive().toString(), window.endExclusive().toString()));
    }

    static String pins(KeyedStateStore.Key key, JdbcRuleDeployments.Deployment deployment, Recipe recipe) {
        return VclCanonical.sha256(String.join("\n", PROFILE, key.digest(), deployment.bundleDigest(),
                recipe.planDigest(), recipe.generation().toString()));
    }

    static UUID id(String pins, String purpose) {
        return UUID.nameUUIDFromBytes((PROFILE + "\n" + pins + "\n" + purpose).getBytes(StandardCharsets.UTF_8));
    }

    static KeyedStateStore.Timer timer(String pins, Recipe recipe) {
        var offset = java.time.Duration.between(recipe.measuredThrough(), recipe.window().endExclusive());
        var purpose = recipe.previousFinal() != null ? "correct:" + recipe.previousFinal()
                : recipe.prefixIndex() == 0 ? "close" : "prefix:" + recipe.prefixIndex();
        return new KeyedStateStore.Timer(id(pins, purpose), KeyedStateStore.TimerKind.WINDOW_CLOSE,
                recipe.dueAt().minus(offset), recipe.state());
    }

    static void stage(JdbcKeyedStateStore store, JdbcRuleDeployments.Deployment deployment, KeyedStateStore.Key key,
            Recipe recipe, long checkpoint, UUID holder, Route route) {
        String pins = pins(key, deployment, recipe);
        store.register(key, pins, recipe.state());
        stageRegistered(store, deployment, key, recipe, checkpoint, holder, route, pins);
    }

    static void stageCorrected(JdbcKeyedStateStore store, JdbcRuleDeployments.Deployment deployment,
            JdbcKeyedStateStore.CorrectedWindow corrected, long checkpoint, UUID holder) {
        var key = corrected.key();
        var recipe = recipe(store.inspect(key, corrected.pins()).state());
        if (!deployment.includesWindow(recipe.window().startInclusive())
                || !corrected.pins().equals(pins(key, deployment, recipe))) {
            throw new VclException("INTERVAL_SCHEDULE_CHANGED", "");
        }
        stageRegistered(store, deployment, key, recipe, checkpoint, holder, null, corrected.pins());
    }

    private static void stageRegistered(JdbcKeyedStateStore store, JdbcRuleDeployments.Deployment deployment, KeyedStateStore.Key key,
            Recipe recipe, long checkpoint, UUID holder, Route route, String pins) {
        var observed = store.inspect(key, pins);
        if (recipe(observed.state()).minimumCheckpoint() >= checkpoint) return;
        var lease = store.acquire(key, pins, holder);
        var current = recipe(lease.snapshot().state());
        if (current.minimumCheckpoint() >= checkpoint) return;
        UUID previous = current.previousFinal();
        if (lease.snapshot().revision() > 0 && lease.timers().isEmpty()) {
            var latest = store.latestSessionMeasure(key, lease).orElse(null);
            if (latest == null) return;
            var result = PARSER.readJson(latest.output().payload().payload().getBytes(StandardCharsets.UTF_8));
            if (!terminalMeasure(result)
                    || checkpoint <= VclObservationReadiness.checkpoint(result.path("checkpoint").textValue())) return;
            previous = latest.output().id();
        }
        var next = current.advance(checkpoint, previous);
        var state = next.state();
        var timers = List.of(timer(pins, next));
        if (route == null) {
            store.commitReviewedInput(key, lease, new KeyedStateStore.Transition(id(pins, "report:" + checkpoint), 1, state.digest(),
                    lease.snapshot().revision(), state, timers, null), recipe.window().startInclusive(), deployment);
            return;
        }
        stageRoute(store, deployment, key, recipe, checkpoint, holder, route, pins, lease, state, timers);
    }

    private static void stageRoute(JdbcKeyedStateStore store, JdbcRuleDeployments.Deployment deployment, KeyedStateStore.Key target,
            Recipe recipe, long checkpoint, UUID holder, Route route, String pins, KeyedStateStore.Lease targetLease,
            KeyedStateStore.State state, List<KeyedStateStore.Timer> timers) {
        var sourceKey = new KeyedStateStore.Key(target.accountId(), target.ruleId(), target.deploymentEpoch(),
                VclCanonical.sha256("VCL_WINDOW_ROUTE_SOURCE/1\n" + route.binding()),
                VclCanonical.sha256("VCL_WINDOW_ROUTE_TARGET/1\n" + target.digest()));
        var initial = KeyedStateStore.State.parse(VclCanonical.encode(JsonNodeFactory.instance.objectNode()
                .put("profile", "vcl-window-route-1").put("target", target.digest())));
        var assignment = KeyedStateStore.State.parse(VclCanonical.encode(JsonNodeFactory.instance.objectNode()
                .put("binding", route.binding().toString()).put("generation", recipe.generation().toString())
                .put("event", route.eventId().toString()).put("eventDigest", route.eventDigest())
                .put("checkpoint", Long.toString(checkpoint)).put("target", target.digest())));
        UUID outputId = id(pins, "route:" + checkpoint);
        var output = new KeyedStateStore.Output(outputId, KeyedStateStore.OutputKind.REKEY, target, assignment);
        var openedAt = recipe.window().startInclusive();
        route.reauthorize().run();
        store.register(sourceKey, pins, initial);
        var sourceLease = store.acquire(sourceKey, pins, holder);
        var receipt = store.inputReceipt(sourceKey, pins, KeyedStateStore.InputKind.OBSERVATION, outputId, 1);
        if (receipt.isEmpty()) {
            store.commitReviewedInput(sourceKey, sourceLease, new KeyedStateStore.Transition(outputId, 1, assignment.digest(),
                    sourceLease.snapshot().revision(), initial, List.of(), null, List.of(output)), openedAt, deployment);
        }
        var saved = store.inspectOutput(sourceKey, pins, outputId);
        if (!saved.output().equals(output)) throw new VclException("WINDOW_ROUTE_CHANGED", "");
        route.reauthorize().run();
        store.consumeWindowRoute(sourceKey, sourceLease, target, targetLease,
                new KeyedStateStore.Transition(outputId, saved.stateRevision(), output.rekeyInputDigest(sourceKey),
                        targetLease.snapshot().revision(), state, timers, null), deployment, openedAt);
    }

    private IntervalWindowSchedule() { }
}
