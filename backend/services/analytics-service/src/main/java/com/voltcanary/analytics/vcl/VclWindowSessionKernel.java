package com.voltcanary.analytics.vcl;

import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclCompilation;
import com.voltcanary.vcl.VclDocument;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclMetadata;
import com.voltcanary.vcl.VclParser;
import com.voltcanary.vcl.VclRegistry;
import com.voltcanary.vcl.VclScalarCompiler;
import com.voltcanary.vcl.VclScalarProgram;
import com.voltcanary.vcl.VclType;
import com.voltcanary.vcl.VclUnits;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Incremental window_session transitions over actual typed observations in one pinned occurrence. */
public final class VclWindowSessionKernel {

    public static final String VERSION = "vcl-window-session-1";
    private static final VclParser PARSER = new VclParser();
    private static final VclRegistry REGISTRY = new VclRegistry(PARSER);
    private static final VclUnits UNITS = VclUnits.standard();
    private final VclScalarEvaluator evaluator = new VclScalarEvaluator(UNITS, REGISTRY.digest());

    public enum Phase {
        UNSEEN,
        LEFT_CENSORED,
        OPEN,
        CLOSED,
        UNKNOWN,
    }

    private enum Truth {
        TRUE,
        FALSE,
        UNKNOWN,
    }

    public record State(
        String planDigest,
        KeyedStateStore.Key key,
        Phase phase,
        Instant firstAt,
        Instant lastAt,
        Instant deadline,
        UUID startWitness,
        UUID closeWitness,
        String reason
    ) {
        public State {
            require(
                planDigest != null &&
                    planDigest.matches("[0-9a-f]{64}") &&
                    key != null &&
                    phase != null,
                "SESSION_STATE_INVALID"
            );
            if (phase == Phase.UNSEEN) {
                require(
                    firstAt == null &&
                        lastAt == null &&
                        deadline == null &&
                        startWitness == null &&
                        closeWitness == null &&
                        reason == null,
                    "SESSION_STATE_INVALID"
                );
            } else {
                require(
                    firstAt != null &&
                        lastAt != null &&
                        deadline != null &&
                        !firstAt.isAfter(lastAt) &&
                        deadline.isAfter(firstAt),
                    "SESSION_STATE_INVALID"
                );
                require(
                    (phase == Phase.CLOSED) == (closeWitness != null) &&
                        (phase == Phase.UNKNOWN) == (reason != null),
                    "SESSION_STATE_INVALID"
                );
                require(
                    (phase != Phase.OPEN && phase != Phase.CLOSED) || startWitness != null,
                    "SESSION_STATE_INVALID"
                );
                require(
                    phase != Phase.LEFT_CENSORED || startWitness == null,
                    "SESSION_STATE_INVALID"
                );
            }
        }
    }

    public record Observation(UUID id, Instant eventTime, Map<UUID, ScalarValue> fields) {
        public Observation {
            require(
                id != null && eventTime != null && fields != null && fields.size() <= 64,
                "SESSION_INPUT_INVALID"
            );
            VclWindowPlan.micros(eventTime);
            fields = Map.copyOf(fields);
        }
    }

    public record Plan(
        String digest,
        String scope,
        UUID rule,
        UUID subjectField,
        UUID occurrenceField,
        long maxDurationUs,
        ScalarDecimalPolicy decimalPolicy,
        VclScalarProgram start,
        VclScalarProgram close
    ) {}

    public static Plan compile(VclCompilation compilation, String nodeId, VclMetadata metadata) {
        require(
            compilation != null && compilation.kind() == VclDocument.Kind.RULE,
            "RULE_REQUIRED"
        );
        require(
            REGISTRY.digest().equals(compilation.registryDigest()) &&
                UNITS.digest().equals(compilation.unitRegistryDigest()),
            "REGISTRY_VERSION_UNAVAILABLE"
        );
        var document = PARSER.parse(compilation.canonicalDocument());
        require(
            compilation.documentDigest().equals(VclCanonical.documentDigest(document)) &&
                compilation.semanticDigest().equals(VclCanonical.semanticDigest(document)),
            "SESSION_PLAN_CHANGED"
        );
        String scope = compilation.accessScopeDigest();
        require(
            scope != null &&
                scope.matches("[0-9a-f]{64}") &&
                scope.equals(metadata.accessScopeDigest()),
            "ACCESS_SCOPE_CHANGED"
        );
        var node = compilation
            .graph()
            .stream()
            .filter(
                candidate ->
                    nodeId.equals(candidate.id()) && "window_session".equals(candidate.method())
            )
            .findFirst()
            .orElseThrow(() -> new VclException("SESSION_OPERATOR_REQUIRED", ""));
        var authored = java.util.stream.StreamSupport.stream(
            document.spec().path("nodes").spliterator(),
            false
        )
            .filter(value -> nodeId.equals(value.path("id").textValue()))
            .findFirst()
            .orElseThrow(() -> new VclException("SESSION_PLAN_CHANGED", ""));
        var config = node.config();
        require(
            node.method().equals(authored.path("method").textValue()) &&
                config.equals(authored.path("config")) &&
                java.util.Objects.equals(
                    node.inputs().get("events"),
                    authored.at("/inputs/events").textValue()
                ),
            "SESSION_PLAN_CHANGED"
        );
        var source = compilation
            .graph()
            .stream()
            .filter(candidate -> candidate.id().equals(node.inputs().get("events")))
            .findFirst()
            .orElseThrow(() -> new VclException("SESSION_PLAN_CHANGED", ""));
        var fields = new LinkedHashMap<UUID, VclType>();
        source.fields().forEach((id, type) -> fields.put(UUID.fromString(id), type));
        UUID subject = UUID.fromString(config.path("subjectFieldId").textValue());
        UUID occurrence = UUID.fromString(config.path("occurrenceFieldId").textValue());
        require(
            fields.containsKey(subject) && fields.containsKey(occurrence),
            "SESSION_PLAN_CHANGED"
        );
        var scalar = new VclScalarCompiler(PARSER, REGISTRY, UNITS);
        var start = scalar.compile(config.path("startWhen").toString(), fields, metadata);
        var close = scalar.compile(config.path("closeWhen").toString(), fields, metadata);
        var decimal = document.spec().path("decimalPolicy");
        var policy = new ScalarDecimalPolicy(
            decimal.path("precision").intValue(),
            decimal.path("scale").intValue(),
            ScalarDecimalPolicy.Rounding.valueOf(decimal.path("rounding").asText())
        );
        long maxDuration = Long.parseLong(config.path("maxDurationUs").asText());
        require(maxDuration > 0, "SESSION_DURATION_INVALID");
        String digest = VclCanonical.sha256(
            String.join(
                "\n",
                VERSION,
                compilation.semanticDigest(),
                nodeId,
                scope,
                VclCanonical.encode(config)
            )
        );
        return new Plan(
            digest,
            scope,
            UUID.fromString(document.spec().path("ruleId").textValue()),
            subject,
            occurrence,
            maxDuration,
            policy,
            start,
            close
        );
    }

    public static State empty(Plan plan, KeyedStateStore.Key key) {
        require(plan.rule().equals(key.ruleId()), "SESSION_OCCURRENCE_CHANGED");
        return new State(plan.digest(), key, Phase.UNSEEN, null, null, null, null, null, null);
    }

    public State observe(Plan plan, State state, Observation observation, String authorizedScope) {
        fence(plan, state, authorizedScope);
        require(
            state
                .key()
                .subjectDigest()
                .equals(identity(observation.fields().get(plan.subjectField()))) &&
                state
                    .key()
                    .occurrenceDigest()
                    .equals(identity(observation.fields().get(plan.occurrenceField()))),
            "SESSION_OCCURRENCE_CHANGED"
        );
        if (terminal(state)) return state;
        var at = observation.eventTime();
        require(
            state.lastAt() == null || !at.isBefore(state.lastAt()),
            "SESSION_CORRECTION_REQUIRED"
        );
        if (state.deadline() != null && !at.isBefore(state.deadline())) return unknown(
            state,
            "SESSION_TIMEOUT"
        );
        var starts = truth(
            evaluator.evaluate(
                plan.start(),
                observation.fields(),
                plan.decimalPolicy(),
                authorizedScope
            )
        );
        var closes = truth(
            evaluator.evaluate(
                plan.close(),
                observation.fields(),
                plan.decimalPolicy(),
                authorizedScope
            )
        );
        if (state.phase() == Phase.UNSEEN) {
            var deadline = at.plus(plan.maxDurationUs(), ChronoUnit.MICROS);
            VclWindowPlan.micros(deadline);
            if (
                starts == Truth.UNKNOWN ||
                closes == Truth.UNKNOWN ||
                (starts == Truth.TRUE && closes == Truth.TRUE)
            ) {
                String reason =
                    starts == Truth.TRUE && closes == Truth.TRUE
                        ? "SESSION_AMBIGUOUS_BOUNDARY"
                        : "SESSION_BOUNDARY_UNKNOWN";
                return new State(
                    state.planDigest(),
                    state.key(),
                    Phase.UNKNOWN,
                    at,
                    at,
                    deadline,
                    null,
                    null,
                    reason
                );
            }
            return new State(
                state.planDigest(),
                state.key(),
                starts == Truth.TRUE ? Phase.OPEN : Phase.LEFT_CENSORED,
                at,
                at,
                deadline,
                starts == Truth.TRUE ? observation.id() : null,
                null,
                null
            );
        }
        if (
            closes == Truth.UNKNOWN ||
            (state.phase() == Phase.LEFT_CENSORED && starts == Truth.UNKNOWN)
        ) {
            return new State(
                state.planDigest(),
                state.key(),
                Phase.UNKNOWN,
                state.firstAt(),
                at,
                state.deadline(),
                state.startWitness(),
                null,
                "SESSION_BOUNDARY_UNKNOWN"
            );
        }
        if (state.phase() == Phase.LEFT_CENSORED && closes == Truth.TRUE) return unknown(
            state,
            "SESSION_LEFT_CENSORED"
        );
        if (state.phase() == Phase.LEFT_CENSORED && starts == Truth.TRUE) {
            var deadline = at.plus(plan.maxDurationUs(), ChronoUnit.MICROS);
            VclWindowPlan.micros(deadline);
            return new State(
                state.planDigest(),
                state.key(),
                Phase.OPEN,
                at,
                at,
                deadline,
                observation.id(),
                null,
                null
            );
        }
        if (state.phase() == Phase.OPEN && closes == Truth.TRUE) {
            if (!at.isAfter(state.firstAt())) return unknown(state, "SESSION_AMBIGUOUS_BOUNDARY");
            return new State(
                state.planDigest(),
                state.key(),
                Phase.CLOSED,
                state.firstAt(),
                at,
                state.deadline(),
                state.startWitness(),
                observation.id(),
                null
            );
        }
        return new State(
            state.planDigest(),
            state.key(),
            state.phase(),
            state.firstAt(),
            at,
            state.deadline(),
            state.startWitness(),
            null,
            null
        );
    }

    public static State timeout(Plan plan, State state, Instant dueAt, String authorizedScope) {
        fence(plan, state, authorizedScope);
        if (terminal(state)) return state;
        require(
            state.deadline() != null && !dueAt.isBefore(state.deadline()),
            "SESSION_TIMER_NOT_DUE"
        );
        return unknown(state, "SESSION_TIMEOUT");
    }

    public static State pause(Plan plan, State state, String authorizedScope) {
        fence(plan, state, authorizedScope);
        return state.phase() == Phase.UNSEEN || terminal(state) ? state : unknown(state, "PAUSED");
    }

    public static String identity(ScalarValue value) {
        require(
            value != null && value.status() == ScalarValue.Status.OK && !value.isNull(),
            "SESSION_IDENTITY_UNKNOWN"
        );
        return VclCanonical.sha256(VclCanonical.encode(value.literal()));
    }

    private static Truth truth(ScalarValue value) {
        if (value.status() == ScalarValue.Status.UNKNOWN || value.isNull()) return Truth.UNKNOWN;
        require(
            value.status() == ScalarValue.Status.OK &&
                "BOOLEAN".equals(value.literal().path("type").textValue()),
            "SESSION_PREDICATE_INVALID"
        );
        return value.literal().path("value").booleanValue() ? Truth.TRUE : Truth.FALSE;
    }

    private static boolean terminal(State state) {
        return state.phase() == Phase.CLOSED || state.phase() == Phase.UNKNOWN;
    }

    private static State unknown(State state, String reason) {
        return new State(
            state.planDigest(),
            state.key(),
            Phase.UNKNOWN,
            state.firstAt(),
            state.lastAt(),
            state.deadline(),
            state.startWitness(),
            null,
            reason
        );
    }

    private static void fence(Plan plan, State state, String authorizedScope) {
        require(plan.scope().equals(authorizedScope), "ACCESS_SCOPE_CHANGED");
        require(
            plan.digest().equals(state.planDigest()) && plan.rule().equals(state.key().ruleId()),
            "SESSION_PLAN_CHANGED"
        );
    }

    private static void require(boolean valid, String code) {
        if (!valid) throw new VclException(code, "");
    }
}
