package com.voltcanary.analytics.vcl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.voltcanary.analytics.vcl.VclDurationWitnessKernel.Context;
import com.voltcanary.analytics.vcl.VclDurationWitnessKernel.Input;
import com.voltcanary.analytics.vcl.VclDurationWitnessKernel.Plan;
import com.voltcanary.analytics.vcl.VclDurationWitnessKernel.Result;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclParser;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Durable single-witness batch profile, not a graph worker or effect authority. Callers must renew
 * actual owner/grant/generation authority before using this port; scope equality alone is not a grant.
 */
public final class DurableDurationWitness {

    public static final String VERSION = "vcl-durable-duration-witness-1";
    public static final int MAX_SAMPLES = 128;
    private static final VclParser PARSER = new VclParser();
    private final KeyedStateStore store;
    private final VclDurationWitnessKernel kernel = new VclDurationWitnessKernel();

    public record Observation(long revision, Input input) {
        public Observation {
            if (revision < 1) throw new IllegalArgumentException("Invalid observation revision");
            Objects.requireNonNull(input);
        }

        @Override
        public String toString() {
            return "WitnessObservation[]";
        }
    }

    public record Outcome(KeyedStateStore.Receipt receipt, Result result) {
        @Override
        public String toString() {
            return "DurableWitnessOutcome[]";
        }
    }

    public DurableDurationWitness(KeyedStateStore store) {
        this.store = Objects.requireNonNull(store);
    }

    public Outcome process(
        Plan plan,
        Context context,
        String pinsDigest,
        Observation observation,
        String authorizedScopeDigest,
        UUID holder
    ) {
        Objects.requireNonNull(holder);
        var preflight = kernel.evaluate(
            plan,
            context,
            List.of(observation.input()),
            authorizedScopeDigest
        );
        if (preflight.value().isFailure()) throw new VclException(preflight.value().reason(), "");
        String contextDigest = contextDigest(context);
        var initial = state(contextDigest, List.of());
        var key = context.partition();
        String inputDigest = VclCanonical.sha256(
            VERSION + "\n" + contextDigest + "\n" + VclCanonical.encode(sample(observation))
        );
        UUID outputId = outputId(
            contextDigest,
            observation.input().observationId(),
            observation.revision()
        );
        store.register(key, pinsDigest, initial);
        var previous = store.inputReceipt(
            key,
            pinsDigest,
            KeyedStateStore.InputKind.OBSERVATION,
            observation.input().observationId(),
            observation.revision()
        );
        if (previous.isPresent()) {
            return replay(previous.get(), key, pinsDigest, outputId, contextDigest, inputDigest);
        }
        var lease = store.acquire(key, pinsDigest, holder);
        var raced = store.inputReceipt(
            key,
            pinsDigest,
            KeyedStateStore.InputKind.OBSERVATION,
            observation.input().observationId(),
            observation.revision()
        );
        if (raced.isPresent()) return replay(
            raced.get(),
            key,
            pinsDigest,
            outputId,
            contextDigest,
            inputDigest
        );
        var observations = restoreState(lease.snapshot().state(), contextDigest, key);
        boolean replaced = false;
        for (int i = 0; i < observations.size(); i++) {
            var old = observations.get(i);
            if (!old.input().observationId().equals(observation.input().observationId())) continue;
            require(observation.revision() > old.revision(), "WITNESS_INPUT_SUPERSEDED");
            observations.set(i, observation);
            replaced = true;
        }
        if (!replaced) {
            require(observations.size() < MAX_SAMPLES, "WITNESS_STATE_INPUT_LIMIT");
            observations.add(observation);
        }
        var result = kernel.evaluate(
            plan,
            context,
            observations.stream().map(Observation::input).toList(),
            authorizedScopeDigest
        );
        if (result.value().isFailure()) throw new VclException(result.value().reason(), "");
        var output = new KeyedStateStore.Output(
            outputId,
            KeyedStateStore.OutputKind.MEASURE,
            null,
            output(contextDigest, inputDigest, result)
        );
        var transition = new KeyedStateStore.Transition(
            observation.input().observationId(),
            observation.revision(),
            inputDigest,
            lease.snapshot().revision(),
            state(contextDigest, observations),
            List.of(),
            null,
            List.of(output)
        );
        var receipt = store.commit(key, lease, transition);
        return new Outcome(receipt, result);
    }

    private Outcome replay(
        KeyedStateStore.InputReceipt receipt,
        KeyedStateStore.Key key,
        String pins,
        UUID outputId,
        String context,
        String input
    ) {
        require(receipt.inputDigest() != null, "WITNESS_LEGACY_RECEIPT_UNAVAILABLE");
        require(receipt.inputDigest().equals(input), "KEY_INPUT_CONFLICT");
        var output = store.inspectOutput(key, pins, outputId);
        require(
            output.output().kind() == KeyedStateStore.OutputKind.MEASURE &&
                output.stateRevision() == receipt.committedRevision() &&
                receipt
                    .outputsDigest()
                    .equals(KeyedStateStore.outputsDigest(List.of(output.output()))),
            "WITNESS_OUTPUT_CHANGED"
        );
        return new Outcome(
            new KeyedStateStore.Receipt(receipt.committedRevision(), true),
            restoreOutput(output.output().payload(), context, input)
        );
    }

    private static String contextDigest(Context context) {
        return VclCanonical.sha256(
            VERSION +
                "\n" +
                context.kernelVersion() +
                "\n" +
                context.planDigest() +
                "\n" +
                context.scopeDigest() +
                "\n" +
                context.partition().digest() +
                "\n" +
                context.window().startInclusive() +
                "\n" +
                context.window().endExclusive()
        );
    }

    private static UUID outputId(String context, UUID input, long revision) {
        return UUID.nameUUIDFromBytes(
            (VERSION + "\n" + context + "\n" + input + "\n" + revision).getBytes(
                StandardCharsets.UTF_8
            )
        );
    }

    private static KeyedStateStore.State state(String context, List<Observation> inputs) {
        var value = JsonNodeFactory.instance
            .objectNode()
            .put("version", VERSION)
            .put("contextDigest", context);
        var samples = value.putArray("samples");
        inputs
            .stream()
            .sorted(Comparator.comparing(input -> input.input().observationId().toString()))
            .forEach(input -> samples.add(sample(input)));
        return KeyedStateStore.State.parse(value.toString());
    }

    private static JsonNode sample(Observation observation) {
        var value = JsonNodeFactory.instance
            .objectNode()
            .put("id", observation.input().observationId().toString())
            .put("revision", Long.toString(observation.revision()))
            .put("eventTime", observation.input().eventTime().toString());
        value.set("predicate", scalar(observation.input().predicate()));
        return value;
    }

    private static ArrayList<Observation> restoreState(
        KeyedStateStore.State state,
        String context,
        KeyedStateStore.Key key
    ) {
        try {
            var value = PARSER.readJson(state.payload().getBytes(StandardCharsets.UTF_8));
            fields(value, Set.of("version", "contextDigest", "samples"));
            require(
                VERSION.equals(value.path("version").textValue()) &&
                    context.equals(value.path("contextDigest").textValue()),
                "WITNESS_STATE_CHANGED"
            );
            require(
                value.path("samples").isArray() && value.path("samples").size() <= MAX_SAMPLES,
                "WITNESS_STATE_CORRUPT"
            );
            var result = new ArrayList<Observation>();
            var ids = new java.util.HashSet<UUID>();
            for (var sample : value.path("samples")) {
                fields(sample, Set.of("id", "revision", "eventTime", "predicate"));
                UUID id = UUID.fromString(sample.path("id").textValue());
                require(ids.add(id), "WITNESS_STATE_CORRUPT");
                long revision = positiveRevision(sample.path("revision"));
                var input = new Input(
                    id,
                    key,
                    Instant.parse(sample.path("eventTime").textValue()),
                    restoreScalar(sample.path("predicate"))
                );
                result.add(new Observation(revision, input));
            }
            return result;
        } catch (
            IllegalArgumentException
            | NullPointerException
            | java.time.DateTimeException failure
        ) {
            throw new VclException("WITNESS_STATE_CORRUPT", "");
        }
    }

    private static KeyedStateStore.State output(String context, String input, Result result) {
        var value = JsonNodeFactory.instance
            .objectNode()
            .put("version", VERSION)
            .put("contextDigest", context)
            .put("inputDigest", input)
            .put("coveredDurationUs", Long.toString(result.coveredDurationUs()));
        value.put(
            "firstObservationId",
            result.firstObservationId() == null ? null : result.firstObservationId().toString()
        );
        value.put(
            "lastObservationId",
            result.lastObservationId() == null ? null : result.lastObservationId().toString()
        );
        value.set("value", scalar(result.value()));
        var evidence = value.putArray("unknownEvidence");
        result.unknownEvidence().forEach(id -> evidence.add(id.toString()));
        return KeyedStateStore.State.parse(value.toString());
    }

    private static Result restoreOutput(KeyedStateStore.State state, String context, String input) {
        try {
            var value = PARSER.readJson(state.payload().getBytes(StandardCharsets.UTF_8));
            fields(
                value,
                Set.of(
                    "version",
                    "contextDigest",
                    "inputDigest",
                    "coveredDurationUs",
                    "firstObservationId",
                    "lastObservationId",
                    "value",
                    "unknownEvidence"
                )
            );
            require(
                VERSION.equals(value.path("version").textValue()) &&
                    context.equals(value.path("contextDigest").textValue()) &&
                    input.equals(value.path("inputDigest").textValue()),
                "WITNESS_OUTPUT_CHANGED"
            );
            var scalar = restoreScalar(value.path("value"));
            long duration = Long.parseLong(value.path("coveredDurationUs").textValue());
            require(duration >= 0 && duration <= 32L * 86_400_000_000L, "WITNESS_OUTPUT_CHANGED");
            UUID first = value.path("firstObservationId").isNull()
                ? null
                : UUID.fromString(value.path("firstObservationId").textValue());
            UUID last = value.path("lastObservationId").isNull()
                ? null
                : UUID.fromString(value.path("lastObservationId").textValue());
            require(
                (first == null) == (last == null) &&
                    (scalar.status() == ScalarValue.Status.UNKNOWN) == (first == null),
                "WITNESS_OUTPUT_CHANGED"
            );
            if (scalar.status() == ScalarValue.Status.OK) require(
                scalar.literal().path("type").asText().equals("BOOLEAN") &&
                    scalar.literal().path("value").asBoolean(),
                "WITNESS_OUTPUT_CHANGED"
            );
            return new Result(
                scalar,
                duration,
                first,
                last,
                evidence(value.path("unknownEvidence"))
            );
        } catch (IllegalArgumentException | NullPointerException failure) {
            throw new VclException("WITNESS_OUTPUT_CHANGED", "");
        }
    }

    private static JsonNode scalar(ScalarValue scalar) {
        var value = JsonNodeFactory.instance
            .objectNode()
            .put("status", scalar.status().name())
            .put("reason", scalar.reason());
        value.set("literal", scalar.literal());
        var evidence = value.putArray("evidence");
        scalar
            .evidence()
            .stream()
            .distinct()
            .sorted(Comparator.comparing(UUID::toString))
            .forEach(id -> evidence.add(id.toString()));
        return value;
    }

    private static ScalarValue restoreScalar(JsonNode value) {
        fields(value, Set.of("status", "reason", "literal", "evidence"));
        var evidence = evidence(value.path("evidence"));
        if (value.path("status").asText().equals("UNKNOWN")) {
            require(value.path("literal").isNull(), "WITNESS_STATE_CORRUPT");
            return ScalarValue.unknown(value.path("reason").textValue(), evidence);
        }
        require(
            value.path("status").asText().equals("OK") &&
                value.path("reason").isNull() &&
                evidence.isEmpty(),
            "WITNESS_STATE_CORRUPT"
        );
        var literal = PARSER.literal(value.path("literal").toString());
        require(
            Set.of("BOOLEAN", "NULL").contains(literal.path("type").asText()),
            "WITNESS_STATE_CORRUPT"
        );
        return ScalarValue.known(literal);
    }

    private static List<UUID> evidence(JsonNode value) {
        require(value.isArray() && value.size() <= 128, "WITNESS_STATE_CORRUPT");
        var result = new ArrayList<UUID>();
        for (var entry : value) result.add(UUID.fromString(entry.textValue()));
        require(result.stream().distinct().count() == result.size(), "WITNESS_STATE_CORRUPT");
        return List.copyOf(result);
    }

    private static long positiveRevision(JsonNode value) {
        require(
            value.isTextual() && value.textValue().matches("[1-9][0-9]{0,18}"),
            "WITNESS_STATE_CORRUPT"
        );
        long result = Long.parseLong(value.textValue());
        require(result > 0, "WITNESS_STATE_CORRUPT");
        return result;
    }

    private static void fields(JsonNode value, Set<String> expected) {
        var actual = new java.util.HashSet<String>();
        value.fieldNames().forEachRemaining(actual::add);
        require(value.isObject() && actual.equals(expected), "WITNESS_STATE_CORRUPT");
    }

    private static void require(boolean condition, String code) {
        if (!condition) throw new VclException(code, "");
    }
}
