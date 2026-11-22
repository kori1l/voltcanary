package com.voltcanary.analytics.vcl;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Infrastructure port only. Callers must authorize deployment/generation/grant pins before use. */
public interface KeyedStateStore {
    int MAX_INBOX = 4096;
    int MAX_STATE_BYTES = 65536;
    int MAX_OUTPUTS = 4096;
    String VERSION = "vcl-keyed-state-1";

    record Key(
        UUID accountId,
        UUID ruleId,
        UUID deploymentEpoch,
        String subjectDigest,
        String occurrenceDigest
    ) {
        public Key {
            Objects.requireNonNull(accountId);
            Objects.requireNonNull(ruleId);
            Objects.requireNonNull(deploymentEpoch);
            checkDigest(subjectDigest);
            checkDigest(occurrenceDigest);
        }

        public String digest() {
            return VclCanonical.sha256(
                VERSION +
                    "\n" +
                    accountId +
                    "\n" +
                    ruleId +
                    "\n" +
                    deploymentEpoch +
                    "\n" +
                    subjectDigest +
                    "\n" +
                    occurrenceDigest
            );
        }

        @Override
        public String toString() {
            return "ExecutionKey[]";
        }
    }

    /** Immutable bounded canonical state; exact numeric values use typed string literals, not JSON numbers. */
    final class State {

        private static final ObjectMapper JSON = new ObjectMapper(
            JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .streamReadConstraints(
                    StreamReadConstraints.builder()
                        .maxNestingDepth(32)
                        .maxStringLength(MAX_STATE_BYTES)
                        .build()
                )
                .build()
        );
        private final String payload;
        private final String digest;

        private State(String payload) {
            if (
                payload == null || payload.getBytes(StandardCharsets.UTF_8).length > MAX_STATE_BYTES
            ) fail("KEY_STATE_LIMIT");
            try (var parser = JSON.createParser(payload)) {
                JsonNode node = JSON.readTree(parser);
                if (node == null || !node.isObject() || parser.nextToken() != null) fail(
                    "KEY_STATE_INVALID"
                );
                validate(node);
                this.payload = VclCanonical.encode(node);
                if (this.payload.getBytes(StandardCharsets.UTF_8).length > MAX_STATE_BYTES) fail(
                    "KEY_STATE_LIMIT"
                );
                digest = VclCanonical.sha256(this.payload);
            } catch (java.io.IOException error) {
                throw new VclException("KEY_STATE_INVALID", "");
            }
        }

        public static State parse(String payload) {
            return new State(payload);
        }

        public static State restore(String payload, String expectedDigest) {
            var state = parse(payload);
            if (!state.digest.equals(expectedDigest) || !state.payload.equals(payload)) fail(
                "KEY_STATE_CORRUPT"
            );
            return state;
        }

        private static void validate(JsonNode node) {
            if (node.isNumber() || node.isBinary() || node.isPojo()) fail("KEY_STATE_INVALID");
            if (
                node.isTextual() && !StandardCharsets.UTF_8.newEncoder().canEncode(node.textValue())
            ) fail("KEY_STATE_INVALID");
            node.fieldNames().forEachRemaining(name -> {
                if (!StandardCharsets.UTF_8.newEncoder().canEncode(name)) fail("KEY_STATE_INVALID");
            });
            node.elements().forEachRemaining(State::validate);
        }

        public String payload() {
            return payload;
        }

        public String digest() {
            return digest;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof State state && payload.equals(state.payload);
        }

        @Override
        public int hashCode() {
            return payload.hashCode();
        }

        @Override
        public String toString() {
            return "ExecutionState[]";
        }
    }

    record Snapshot(long revision, State state) {
        public Snapshot {
            if (revision < 0) throw new IllegalArgumentException("Negative state revision");
            Objects.requireNonNull(state);
        }

        @Override
        public String toString() {
            return "KeySnapshot[revision=" + revision + "]";
        }
    }

    enum TimerKind {
        WAIT_TIMEOUT,
        WINDOW_CLOSE,
        SESSION_TIMEOUT,
    }

    record Timer(UUID id, TimerKind kind, Instant dueAt, State payload) {
        public Timer {
            Objects.requireNonNull(id);
            Objects.requireNonNull(kind);
            VclWindowPlan.micros(dueAt);
            Objects.requireNonNull(payload);
        }

        public String digest() {
            return VclCanonical.sha256(id + "\n" + kind + "\n" + dueAt + "\n" + payload.digest());
        }

        @Override
        public String toString() {
            return "KeyTimer[kind=" + kind + "]";
        }
    }

    record ScheduledTimer(Timer timer, long stateRevision) {
        public ScheduledTimer {
            Objects.requireNonNull(timer);
            if (stateRevision < 1) throw new IllegalArgumentException("Invalid timer revision");
        }
    }

    record DueTimer(Key key, String pinsDigest, ScheduledTimer scheduled) {
        public DueTimer {
            Objects.requireNonNull(key);
            checkDigest(pinsDigest);
            Objects.requireNonNull(scheduled);
        }

        @Override
        public String toString() {
            return "DueKeyTimer[]";
        }
    }

    record TimerInput(UUID timerId, long scheduledRevision) {
        public TimerInput {
            Objects.requireNonNull(timerId);
            if (scheduledRevision < 1) throw new IllegalArgumentException("Invalid timer revision");
        }
    }

    enum OutputKind {
        MEASURE,
        REKEY,
        ALERT_PROPOSAL,
    }

    record Output(UUID id, OutputKind kind, Key target, State payload) {
        public Output {
            Objects.requireNonNull(id);
            Objects.requireNonNull(kind);
            Objects.requireNonNull(payload);
            if ((kind == OutputKind.REKEY) != (target != null)) throw new IllegalArgumentException(
                "Invalid output target"
            );
        }

        public String digest() {
            return VclCanonical.sha256(
                id +
                    "\n" +
                    kind +
                    "\n" +
                    (target == null ? "none" : target.digest()) +
                    "\n" +
                    payload.digest()
            );
        }

        /** Namespaces a local rekey input by its actual source, rather than by output UUID alone. */
        public String rekeyInputDigest(Key source) {
            if (kind != OutputKind.REKEY) throw new IllegalArgumentException(
                "Rekey output required"
            );
            return VclCanonical.sha256("vcl-keyed-rekey-1\n" + source.digest() + "\n" + digest());
        }

        @Override
        public String toString() {
            return "KeyOutput[kind=" + kind + "]";
        }
    }

    record OutputStatus(Output output, long stateRevision, UUID receiptId, String receiptDigest) {
        public OutputStatus {
            Objects.requireNonNull(output);
            if (
                stateRevision < 1 || (receiptId == null) != (receiptDigest == null)
            ) throw new IllegalArgumentException("Invalid output status");
            if (receiptDigest != null) checkDigest(receiptDigest);
        }

        @Override
        public String toString() {
            return "KeyOutputStatus[]";
        }
    }

    record Acknowledgement(
        UUID outputId,
        String outputDigest,
        UUID receiptId,
        String receiptDigest
    ) {
        public Acknowledgement {
            Objects.requireNonNull(outputId);
            checkDigest(outputDigest);
            Objects.requireNonNull(receiptId);
            checkDigest(receiptDigest);
        }

        @Override
        public String toString() {
            return "KeyOutputAcknowledgement[]";
        }
    }

    record Lease(
        UUID holderId,
        long fence,
        String keyDigest,
        String pinsDigest,
        Instant expiresAt,
        Snapshot snapshot,
        List<ScheduledTimer> timers
    ) {
        public Lease {
            Objects.requireNonNull(holderId);
            if (fence < 1) throw new IllegalArgumentException("Invalid lease fence");
            checkDigest(keyDigest);
            checkDigest(pinsDigest);
            Objects.requireNonNull(expiresAt);
            Objects.requireNonNull(snapshot);
            timers = List.copyOf(timers);
        }

        @Override
        public String toString() {
            return "KeyLease[fence=" + fence + "]";
        }
    }

    /** timers is the complete next schedule, not a patch; consumedTimer binds an actual due schedule revision. */
    record Transition(
        UUID inputId,
        long inputRevision,
        String inputDigest,
        long expectedRevision,
        State next,
        List<Timer> timers,
        TimerInput consumedTimer,
        List<Output> outputs
    ) {
        public Transition(
            UUID inputId,
            long inputRevision,
            String inputDigest,
            long expectedRevision,
            State next
        ) {
            this(
                inputId,
                inputRevision,
                inputDigest,
                expectedRevision,
                next,
                List.of(),
                null,
                List.of()
            );
        }

        public Transition(
            UUID inputId,
            long inputRevision,
            String inputDigest,
            long expectedRevision,
            State next,
            List<Timer> timers,
            TimerInput consumedTimer
        ) {
            this(
                inputId,
                inputRevision,
                inputDigest,
                expectedRevision,
                next,
                timers,
                consumedTimer,
                List.of()
            );
        }

        public Transition {
            Objects.requireNonNull(inputId);
            if (
                inputRevision < 1 || expectedRevision < 0 || expectedRevision == Long.MAX_VALUE
            ) throw new IllegalArgumentException("Invalid transition revision");
            checkDigest(inputDigest);
            Objects.requireNonNull(next);
            if (timers == null || timers.size() > 64) throw new IllegalArgumentException(
                "Invalid timer budget"
            );
            timers = timers
                .stream()
                .sorted(Comparator.comparing(timer -> timer.id().toString()))
                .toList();
            var ids = new HashSet<UUID>();
            int bytes = 0;
            for (var timer : timers) {
                if (!ids.add(timer.id())) throw new IllegalArgumentException("Duplicate timer");
                bytes += timer.payload().payload().getBytes(StandardCharsets.UTF_8).length;
                if (
                    consumedTimer != null && timer.id().equals(consumedTimer.timerId())
                ) throw new IllegalArgumentException("Consumed timer cannot rearm itself");
            }
            if (outputs == null || outputs.size() > 64) throw new IllegalArgumentException(
                "Invalid output budget"
            );
            outputs = outputs
                .stream()
                .sorted(Comparator.comparing(output -> output.id().toString()))
                .toList();
            ids.clear();
            for (var output : outputs) {
                if (!ids.add(output.id())) throw new IllegalArgumentException("Duplicate output");
                bytes += output.payload().payload().getBytes(StandardCharsets.UTF_8).length;
            }
            if (bytes > 262144) throw new IllegalArgumentException(
                "Invalid transition byte budget"
            );
            if (
                consumedTimer != null &&
                (!inputId.equals(consumedTimer.timerId()) ||
                    inputRevision != consumedTimer.scheduledRevision())
            ) {
                throw new IllegalArgumentException("Timer input identity mismatch");
            }
        }

        public String digest() {
            String base =
                VERSION +
                "\n" +
                inputId +
                "\n" +
                inputRevision +
                "\n" +
                inputDigest +
                "\n" +
                expectedRevision +
                "\n" +
                next.digest();
            if (!timers.isEmpty() || consumedTimer != null) {
                String schedule = timers
                    .stream()
                    .map(Timer::digest)
                    .collect(java.util.stream.Collectors.joining("\n"));
                base +=
                    "\ntimer-snapshot-1\n" +
                    schedule +
                    "\n" +
                    (consumedTimer == null
                        ? "none"
                        : consumedTimer.timerId() + ":" + consumedTimer.scheduledRevision());
            }
            if (!outputs.isEmpty()) base +=
                "\nkeyed-outbox-1\n" +
                outputs
                    .stream()
                    .map(Output::digest)
                    .collect(java.util.stream.Collectors.joining("\n"));
            return VclCanonical.sha256(base);
        }

        @Override
        public String toString() {
            return "KeyTransition[]";
        }
    }

    record Receipt(long committedRevision, boolean replay) {}

    enum InputKind {
        OBSERVATION,
        TIMER,
    }

    /** Legacy rows can lack inputDigest; they only support replay of the exact original transition. */
    record InputReceipt(
        String inputDigest,
        String outputsDigest,
        String transitionDigest,
        long committedRevision
    ) {
        public InputReceipt {
            if (inputDigest != null) checkDigest(inputDigest);
            if (outputsDigest != null) checkDigest(outputsDigest);
            if (
                (inputDigest == null) != (outputsDigest == null)
            ) throw new IllegalArgumentException("Invalid input receipt digests");
            checkDigest(transitionDigest);
            if (committedRevision < 1) throw new IllegalArgumentException(
                "Invalid input receipt revision"
            );
        }

        @Override
        public String toString() {
            return "KeyInputReceipt[]";
        }
    }

    record RekeyReceipt(UUID id, String digest, Receipt target) {
        public RekeyReceipt {
            Objects.requireNonNull(id);
            checkDigest(digest);
            Objects.requireNonNull(target);
        }

        @Override
        public String toString() {
            return "KeyRekeyReceipt[]";
        }
    }

    Snapshot register(Key key, String pinsDigest, State initial);
    Lease acquire(Key key, String pinsDigest, UUID holderId);
    Receipt commit(Key key, Lease lease, Transition transition);
    java.util.Optional<InputReceipt> inputReceipt(
        Key key,
        String pinsDigest,
        InputKind kind,
        UUID inputId,
        long inputRevision
    );
    Snapshot inspect(Key key, String pinsDigest);
    /** Discovery only; every candidate must reacquire a key lease and recheck its schedule revision. */
    List<DueTimer> dueTimers(UUID accountId, int limit);
    List<DueTimer> dueTimers(UUID accountId, UUID ruleId, UUID deploymentEpoch, int limit);
    List<DueTimer> dueTimers(
        UUID accountId,
        UUID ruleId,
        UUID deploymentEpoch,
        TimerKind kind,
        DueTimer after,
        int limit
    );
    List<OutputStatus> pendingOutputs(Key key, String pinsDigest, int limit);
    OutputStatus inspectOutput(Key key, String pinsDigest, UUID outputId);
    /** Acknowledge local outputs after verifying their owning receipts. Alert proposals use owner delivery. */
    int acknowledge(Key key, Lease lease, List<Acknowledgement> acknowledgements);
    /**
     * Atomically consumes the actual source REKEY output into a supplied target transition and ACKs it.
     * Both keys require current caller authority before use. The input ID/revision/digest must bind the
     * stored output ID/original revision/rekeyInputDigest; this operation creates its own local receipt.
     */
    RekeyReceipt consumeRekey(
        Key source,
        Lease sourceLease,
        Key target,
        Lease targetLease,
        Transition transition
    );

    static String outputsDigest(List<Output> outputs) {
        return VclCanonical.sha256(
            "vcl-keyed-input-outputs-1\n" +
                outputs
                    .stream()
                    .sorted(Comparator.comparing(output -> output.id().toString()))
                    .map(Output::digest)
                    .collect(java.util.stream.Collectors.joining("\n"))
        );
    }

    private static void checkDigest(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException(
            "Invalid execution digest"
        );
    }

    private static void fail(String code) {
        throw new VclException(code, "");
    }
}
