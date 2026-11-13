package com.voltcanary.aiagent.trace;

import com.voltcanary.aiagent.port.AssistantTraceRecorder;
import java.util.Map;
import java.util.UUID;

/** Synchronous request-local observable spans. No prompts, headers or private reasoning are captured. */
public final class AssistantTrace {
    private static final ThreadLocal<Session> CURRENT = new ThreadLocal<>();
    private AssistantTrace() { }

    public static Session open(AssistantTraceRecorder recorder, AssistantTraceRecorder.Correlation correlation) {
        var session = new Session(recorder, correlation, CURRENT.get());
        CURRENT.set(session);
        return session;
    }

    public static Step start(String stage, Map<String, Object> details) {
        return new Step(CURRENT.get(), stage, details);
    }

    public static void modelAttempt(UUID attempt) {
        var session = CURRENT.get();
        if (session != null) session.lastModelAttempt = attempt;
    }

    public static void event(String stage, String status, String code, Map<String, Object> details) {
        var session = CURRENT.get();
        if (session != null) {
            UUID attempt = session.lastModelAttempt != null && java.util.Set.of("SANITIZATION", "MODEL_RUN", "PROVIDER_USAGE", "COMPILATION", "VALIDATION", "CANDIDATE").contains(stage)
                    ? session.lastModelAttempt : session.attempt;
            session.write(attempt, stage, status, code, null, details, false);
        }
    }

    public static final class Session implements AutoCloseable {
        private final AssistantTraceRecorder recorder;
        private final AssistantTraceRecorder.Correlation correlation;
        private final Session previous;
        private UUID attempt = UUID.randomUUID();
        private UUID lastModelAttempt;
        private boolean detailLoss;
        private final java.util.List<Deferred> deferred = new java.util.ArrayList<>();
        private record Deferred(UUID attempt, String stage, String status, String code, Long duration, Map<String, Object> details) { }
        private Session(AssistantTraceRecorder recorder, AssistantTraceRecorder.Correlation correlation, Session previous) {
            this.recorder = recorder;
            this.correlation = correlation;
            this.previous = previous;
        }
        private void write(UUID id, String stage, String status, String code, Long duration, Map<String, Object> details, boolean required) {
            try {
                flush();
                if (detailLoss) {
                    recorder.append(correlation, id, "CAPTURE", "UNKNOWN", "DETAIL_LOSS", null, Map.of());
                    detailLoss = false;
                }
                recorder.append(correlation, id, stage, status, code, duration, details);
            } catch (RuntimeException unavailable) {
                if (!required && java.util.Set.of("TURN", "MODEL_CALL", "TOOL_EXECUTION", "EXECUTION", "RECONCILIATION").contains(stage)
                        && java.util.Set.of("SUCCEEDED", "FAILED", "UNKNOWN").contains(status)) {
                    deferred.add(new Deferred(id, stage, status, code, duration, new java.util.LinkedHashMap<>(details)));
                } else if (!required) detailLoss = true;
                if (required) throw unavailable;
                // A failed outcome write must never cause a repeated provider call or domain action.
            }
        }
        private void flush() {
            while (!deferred.isEmpty()) {
                var event = deferred.getFirst();
                recorder.append(correlation, event.attempt(), event.stage(), event.status(), event.code(), event.duration(), event.details());
                deferred.removeFirst();
            }
        }
        @Override public void close() {
            try { flush(); } catch (RuntimeException unavailable) { /* Receipts recover unfinished required checkpoints. */ }
            if (detailLoss) {
                try { recorder.append(correlation, attempt, "CAPTURE", "UNKNOWN", "DETAIL_LOSS", null, Map.of()); }
                catch (RuntimeException unavailable) { /* Unfinished start checkpoints remain visible to reconciliation. */ }
            }
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
        }
    }

    public static final class Step implements AutoCloseable {
        private final Session session;
        private final UUID id = UUID.randomUUID();
        private final UUID previousAttempt;
        private final String stage;
        private final Map<String, Object> references;
        private final long started = System.nanoTime();
        private boolean finished;
        private Step(Session session, String stage, Map<String, Object> details) {
            this.session = session;
            this.stage = stage;
            this.references = new java.util.LinkedHashMap<>(details);
            previousAttempt = session == null ? null : session.attempt;
            if (session != null) {
                references.put("parentAttemptId", previousAttempt);
                session.write(id, stage, "STARTED", null, null, references, true);
                session.attempt = id;
                if ("MODEL_CALL".equals(stage)) session.lastModelAttempt = id;
            }
        }
        public void success(Map<String, Object> details) { finish("SUCCEEDED", null, details); }
        public void failure(String code, Map<String, Object> details) { finish("FAILED", code, details); }
        private void finish(String status, String code, Map<String, Object> details) {
            if (finished) return;
            finished = true;
            if (session != null) {
                var merged = new java.util.LinkedHashMap<>(references);
                merged.putAll(details);
                session.write(id, stage, status, code, (System.nanoTime() - started) / 1000000, merged, false);
            }
        }
        @Override public void close() {
            if (!finished) finish("FAILED", "STAGE_INTERRUPTED", Map.of());
            if (session != null) session.attempt = previousAttempt;
        }
    }
}
