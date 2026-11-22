package com.voltcanary.analytics.vcl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclParser;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

/** Durable per-subject cursor and append-only threshold crossings. */
public final class DurableSustained {

    public static final String PROFILE = "vcl-durable-sustained-1";
    private static final VclParser PARSER = new VclParser();
    private final KeyedStateStore store;
    private final VclSustainedPlan plan;
    private final KeyedStateStore.Key key;
    private final String pins;
    private final Runnable authorize;
    private final DurableWindowSession.Commit commit;

    @FunctionalInterface
    public interface Replay {
        List<VclSustainedPlan.Sample> read(Instant from, Instant through);
    }

    public record JournalPin(UUID generation, String checkpoint) {}

    public DurableSustained(
        KeyedStateStore store,
        VclSustainedPlan plan,
        KeyedStateStore.Key key,
        String ownerPins,
        Runnable authorize,
        DurableWindowSession.Commit commit
    ) {
        this.store = store;
        this.plan = plan;
        this.key = key;
        this.authorize = authorize;
        this.commit = commit;
        this.pins = VclCanonical.sha256(
            String.join("\n", PROFILE, plan.digest(), key.digest(), ownerPins)
        );
    }

    public KeyedStateStore.Receipt observe(
        VclSustainedPlan.Sample sample,
        String rowDigest,
        String scope,
        UUID holder,
        JournalPin journal,
        Replay history
    ) {
        authorize.run();
        var point = plan.point(key, sample, scope);
        String digest = VclCanonical.sha256(
            String.join("\n", pins, sample.id().toString(), rowDigest)
        );
        store.register(key, pins, encode(VclSustainedPlan.Cursor.empty(), null));
        var replay = replay(sample.id(), digest);
        if (replay != null) return replay;
        var lease = store.acquire(key, pins, holder);
        replay = replay(sample.id(), digest);
        if (replay != null) return replay;
        var before = decode(lease.snapshot().state());
        var openedAt = before.openedAt() == null ? sample.at() : before.openedAt();
        VclSustainedPlan.Cursor cursor;
        List<KeyedStateStore.Output> outputs;
        var pending = before.cursor().pending();
        if (point != null && pending != null && point.at().isBefore(pending.at())) {
            var from = point.at().isBefore(openedAt) ? point.at() : openedAt;
            var correction = replayHistory(sample, scope, history, from, pending.at(), journal);
            cursor = correction.cursor();
            outputs = correction.outputs();
        } else {
            var next = plan.advance(key, before.cursor(), point);
            cursor = next.cursor();
            outputs =
                next.episode() == null ? List.of() : List.of(output(next.episode(), journal, null));
        }
        authorize.run();
        return commit.apply(
            key,
            lease,
            new KeyedStateStore.Transition(
                sample.id(),
                1,
                digest,
                lease.snapshot().revision(),
                encode(cursor, sample.at().isBefore(openedAt) ? sample.at() : openedAt),
                List.of(),
                null,
                outputs
            ),
            openedAt
        );
    }

    private record Correction(
        VclSustainedPlan.Cursor cursor,
        List<KeyedStateStore.Output> outputs
    ) {}

    private Correction replayHistory(
        VclSustainedPlan.Sample trigger,
        String scope,
        Replay history,
        Instant from,
        Instant through,
        JournalPin journal
    ) {
        var rows = history.read(from, through);
        if (rows.size() > VclDurationWitnessKernel.MAX_INPUTS) throw new VclException(
            "WITNESS_INPUT_LIMIT",
            ""
        );
        var episodes = new LinkedHashMap<String, VclDurationWitnessKernel.Episode>();
        var previousEpisodes = new LinkedHashMap<String, VclDurationWitnessKernel.Episode>();
        var cursor = VclSustainedPlan.Cursor.empty();
        var previousCursor = VclSustainedPlan.Cursor.empty();
        boolean seen = false;
        var ids = new java.util.HashSet<UUID>();
        for (var row : rows
            .stream()
            .sorted(
                Comparator.comparing(VclSustainedPlan.Sample::at).thenComparing(sample ->
                    sample.id().toString()
                )
            )
            .toList()) {
            if (!ids.add(row.id()) || row.at().isBefore(from) || row.at().isAfter(through)) {
                throw new VclException("SUSTAINED_REPLAY_INPUT_CHANGED", "");
            }
            if (trigger.id().equals(row.id())) {
                seen =
                    trigger.at().equals(row.at()) && trigger.recordedAt().equals(row.recordedAt());
            }
            var point = plan.point(key, row, scope);
            if (!trigger.id().equals(row.id())) {
                var previous = plan.advance(key, previousCursor, point);
                previousCursor = previous.cursor();
                if (previous.episode() != null) previousEpisodes.put(
                    previous.episode().occurrenceDigest(),
                    previous.episode()
                );
            }
            var next = plan.advance(key, cursor, point);
            cursor = next.cursor();
            if (next.episode() != null) episodes.put(
                next.episode().occurrenceDigest(),
                next.episode()
            );
        }
        if (!seen || cursor.pending() == null || !through.equals(cursor.pending().at())) {
            throw new VclException("SUSTAINED_REPLAY_INPUT_CHANGED", "");
        }
        var payload = JsonNodeFactory.instance
            .objectNode()
            .put("profile", "vcl-sustained-correction-1")
            .put("trigger", trigger.id().toString())
            .put("from", from.toString())
            .put("through", through.toString())
            .put("generation", journal.generation().toString())
            .put("checkpoint", journal.checkpoint());
        var revisions = payload.putArray("episodes");
        var removed = payload.putArray("removed");
        var outputs = new ArrayList<KeyedStateStore.Output>();
        episodes.forEach((occurrence, episode) -> {
            if (episode.equals(previousEpisodes.get(occurrence))) return;
            revisions.add(episodePayload(episode));
            outputs.add(output(episode, journal, trigger.id()));
        });
        previousEpisodes.forEach((occurrence, episode) -> {
            if (episodes.containsKey(occurrence)) return;
            removed.add(episodePayload(episode));
            var retraction = episodePayload(episode)
                .put("profile", PROFILE)
                .put("generation", journal.generation().toString())
                .put("checkpoint", journal.checkpoint())
                .put("correction", trigger.id().toString())
                .put("replayFrom", from.toString())
                .put("replayThrough", through.toString());
            UUID retractionId = UUID.nameUUIDFromBytes(
                (pins + "\nremoved\n" + occurrence + "\n" + trigger.id()).getBytes(
                    StandardCharsets.UTF_8
                )
            );
            outputs.add(
                new KeyedStateStore.Output(
                    retractionId,
                    KeyedStateStore.OutputKind.MEASURE,
                    null,
                    state(retraction)
                )
            );
        });
        UUID id = UUID.nameUUIDFromBytes(
            (pins + "\ncorrection\n" + trigger.id()).getBytes(StandardCharsets.UTF_8)
        );
        outputs.add(
            new KeyedStateStore.Output(id, KeyedStateStore.OutputKind.MEASURE, null, state(payload))
        );
        return new Correction(cursor, List.copyOf(outputs));
    }

    private KeyedStateStore.Receipt replay(UUID id, String digest) {
        var receipt = store.inputReceipt(key, pins, KeyedStateStore.InputKind.OBSERVATION, id, 1);
        if (receipt.isEmpty()) return null;
        if (!digest.equals(receipt.get().inputDigest())) throw new VclException(
            "KEY_INPUT_CONFLICT",
            ""
        );
        authorize.run();
        return new KeyedStateStore.Receipt(receipt.get().committedRevision(), true);
    }

    private KeyedStateStore.Output output(
        VclDurationWitnessKernel.Episode episode,
        JournalPin journal,
        UUID correction
    ) {
        var payload = episodePayload(episode)
            .put("profile", PROFILE)
            .put("generation", journal.generation().toString())
            .put("checkpoint", journal.checkpoint());
        String identity = pins + "\n" + episode.occurrenceDigest();
        if (correction != null) {
            payload.put("correction", correction.toString());
            identity += "\n" + correction;
        }
        UUID id = UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
        return new KeyedStateStore.Output(
            id,
            KeyedStateStore.OutputKind.MEASURE,
            null,
            state(payload)
        );
    }

    private static com.fasterxml.jackson.databind.node.ObjectNode episodePayload(
        VclDurationWitnessKernel.Episode episode
    ) {
        return JsonNodeFactory.instance
            .objectNode()
            .put("occurrence", episode.occurrenceDigest())
            .put("firstObservation", episode.firstObservation().toString())
            .put("qualifyingObservation", episode.qualifyingObservation().toString())
            .put("startedAt", episode.startedAt().toString())
            .put("qualifiedAt", episode.qualifiedAt().toString())
            .put("coveredDurationUs", Long.toString(episode.coveredDurationUs()));
    }

    private record Stored(VclSustainedPlan.Cursor cursor, Instant openedAt) {}

    private KeyedStateStore.State encode(VclSustainedPlan.Cursor cursor, Instant openedAt) {
        var value = JsonNodeFactory.instance
            .objectNode()
            .put("profile", PROFILE)
            .put("pins", pins)
            .put("openedAt", text(openedAt))
            .put("start", text(cursor.start()))
            .put("startedAt", text(cursor.startedAt()))
            .put("emitted", cursor.emitted());
        var point = cursor.pending();
        if (point == null) value.putNull("pending");
        else value
            .putObject("pending")
            .put("id", point.id().toString())
            .put("at", point.at().toString())
            .put("truth", point.truth().name());
        return state(value);
    }

    private Stored decode(KeyedStateStore.State state) {
        try {
            var value = PARSER.readJson(state.payload().getBytes(StandardCharsets.UTF_8));
            if (
                value.size() != 7 ||
                !PROFILE.equals(value.path("profile").textValue()) ||
                !pins.equals(value.path("pins").textValue()) ||
                !value.path("emitted").isBoolean()
            ) {
                throw new IllegalArgumentException();
            }
            var pending = value.path("pending");
            var point = pending.isNull()
                ? null
                : new VclSustainedPlan.Point(
                      UUID.fromString(pending.path("id").textValue()),
                      Instant.parse(pending.path("at").textValue()),
                      VclSustainedPlan.Truth.valueOf(pending.path("truth").textValue())
                  );
            UUID start = value.path("start").isNull()
                ? null
                : UUID.fromString(value.path("start").textValue());
            Instant startedAt = instant(value.path("startedAt"));
            boolean emitted = value.path("emitted").booleanValue();
            if (
                (start == null) != (startedAt == null) ||
                (start == null && emitted) ||
                (start != null && (point == null || !startedAt.isBefore(point.at())))
            ) throw new IllegalArgumentException();
            return new Stored(
                new VclSustainedPlan.Cursor(point, start, startedAt, emitted),
                instant(value.path("openedAt"))
            );
        } catch (RuntimeException invalid) {
            throw new VclException("SUSTAINED_STATE_INVALID", "");
        }
    }

    private static KeyedStateStore.State state(JsonNode value) {
        return KeyedStateStore.State.parse(VclCanonical.encode(value));
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    private static Instant instant(JsonNode value) {
        return value.isNull() ? null : Instant.parse(value.textValue());
    }
}
