package com.voltcanary.analytics.vcl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclCompilation;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclParser;
import com.voltcanary.vcl.VclRegistry;
import com.voltcanary.vcl.VclType;
import com.voltcanary.vcl.VclUnits;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/** Pure reduction of a supplied subject batch; no coverage, finalization or emission permission. */
public final class VclWindowAggregateKernel {
    public static final String VERSION = "vcl-window-aggregate-1";
    public static final int MAX_INPUTS = 4096;
    private static final VclParser PARSER = new VclParser();
    private static final String REGISTRY_DIGEST = new VclRegistry(PARSER).digest();

    /** COUNT has no value field and requires value=null; all other reducers require a ScalarValue. */
    public record Input(UUID observationId, Instant eventTime, ScalarValue value) {
        public Input { Objects.requireNonNull(observationId); }
        @Override public String toString() { return "WindowAggregateInput[]"; }
    }

    public record Context(String kernelVersion, String planDigest, String scopeDigest, VclWindowPlan.Window window) {
        public Context {
            Objects.requireNonNull(kernelVersion);
            if (planDigest == null || !planDigest.matches("[0-9a-f]{64}")
                    || scopeDigest == null || !scopeDigest.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Invalid aggregate pins");
            }
            Objects.requireNonNull(window);
        }
        @Override public String toString() { return "WindowAggregateContext[kernelVersion=" + kernelVersion + "]"; }
    }

    /** Immutable projection of checked graph IR, including the predecessor's actual field type. */
    public static final class Plan {
        private final String reducer;
        private final VclType inputType;
        private final VclType outputType;
        private final ScalarDecimalPolicy decimalPolicy;
        private final VclWindowPlan windows;
        private final String digest;
        private final String scopeDigest;

        private Plan(VclCompilation.Node node, VclType inputType, ScalarDecimalPolicy policy, VclCompilation compilation) {
            require(node != null && "window_aggregate".equals(node.method()), "WINDOW_OPERATOR_REQUIRED");
            var config = node.config();
            reducer = config.path("reducer").asText();
            require(Set.of("COUNT", "SUM", "MIN", "MAX", "AVG").contains(reducer), "REDUCER_INVALID");
            boolean count = reducer.equals("COUNT");
            require(config.has("valueFieldId") && count == config.path("valueFieldId").isNull(), "REDUCER_FIELD_MISMATCH");
            require(count == (inputType == null), "REDUCER_FIELD_MISMATCH");
            this.inputType = inputType;
            decimalPolicy = Objects.requireNonNull(policy);
            VclType result = count ? new VclType(VclType.Kind.INT64, false, "1", null) : inputType;
            if (!count) {
                if (Set.of("SUM", "AVG").contains(reducer)) {
                    require(inputType.numeric() || inputType.kind() == VclType.Kind.DURATION, "TYPE_MISMATCH");
                } else {
                    require(Set.of(VclType.Kind.INT64, VclType.Kind.DECIMAL, VclType.Kind.DURATION,
                            VclType.Kind.DATE, VclType.Kind.INSTANT, VclType.Kind.TEXT).contains(inputType.kind()), "TYPE_NOT_ORDERED");
                }
                if (inputType.numeric()) {
                    var unit = VclUnits.standard().require(inputType.unit(), "");
                    require(!reducer.equals("SUM") || !unit.absolute(), "UNIT_ARITHMETIC_UNSUPPORTED");
                }
                if (reducer.equals("AVG") && inputType.kind() == VclType.Kind.INT64) {
                    result = new VclType(VclType.Kind.DECIMAL, inputType.nullable(), inputType.unit(), null);
                }
            }
            outputType = result;
            require(outputType.equals(node.fields().get(config.path("measureFieldId").asText())), "AGGREGATE_OUTPUT_TYPE_CHANGED");
            windows = VclWindowPlan.compile(node);
            scopeDigest = compilation.accessScopeDigest();
            digest = VclCanonical.sha256(VERSION + "\n" + VclCanonical.encode(config) + "\n" + windows.digest()
                    + "\n" + inputType + "\n" + outputType + "\n" + policy + "\n" + compilation.semanticDigest()
                    + "\n" + scopeDigest + "\n" + compilation.registryDigest() + "\n" + compilation.unitRegistryDigest());
        }

        public static Plan compile(VclCompilation compilation, String nodeId, ScalarDecimalPolicy policy) {
            require(compilation != null && REGISTRY_DIGEST.equals(compilation.registryDigest())
                    && VclUnits.standard().digest().equals(compilation.unitRegistryDigest()), "REGISTRY_VERSION_UNAVAILABLE");
            require(compilation.accessScopeDigest() != null && compilation.accessScopeDigest().matches("[0-9a-f]{64}"), "ACCESS_SCOPE_REQUIRED");
            var declaration = PARSER.parse(compilation.canonicalDocument()).spec().path("decimalPolicy");
            require(policy != null && declaration.path("precision").asInt() == policy.precision()
                    && declaration.path("scale").asInt() == policy.scale()
                    && declaration.path("rounding").asText().equals(policy.rounding().name()), "DECIMAL_POLICY_CHANGED");
            var matches = compilation.graph().stream().filter(node -> node.id().equals(nodeId)).toList();
            require(matches.size() == 1 && matches.getFirst().method().equals("window_aggregate"), "WINDOW_OPERATOR_REQUIRED");
            var node = matches.getFirst();
            String sourceId = node.inputs().get("events");
            var sources = compilation.graph().stream().filter(source -> source.id().equals(sourceId)).toList();
            require(sources.size() == 1 && sources.getFirst().outputKind().equals("EVENT"), "AGGREGATE_INPUT_REQUIRED");
            VclType type = node.config().path("reducer").asText().equals("COUNT") ? null
                    : sources.getFirst().fields().get(node.config().path("valueFieldId").asText());
            return new Plan(node, type, policy, compilation);
        }
        public String digest() { return digest; }
        public VclType outputType() { return outputType; }
        public VclWindowPlan windows() { return windows; }
        @Override public String toString() { return "WindowAggregatePlan[reducer=" + reducer + ", version=" + VERSION + "]"; }
    }

    public ScalarValue evaluate(Plan plan, Context context, List<Input> inputs, String authorizedScopeDigest) {
        if (!context.scopeDigest().equals(authorizedScopeDigest) || !plan.scopeDigest.equals(authorizedScopeDigest)) {
            return failure(ScalarValue.Status.ACCESS_REVOKED, "ACCESS_SCOPE_CHANGED");
        }
        if (!VERSION.equals(context.kernelVersion())) return failure(ScalarValue.Status.SCHEMA_UNAVAILABLE, "AGGREGATE_KERNEL_UNAVAILABLE");
        if (!plan.digest().equals(context.planDigest())) return failure(ScalarValue.Status.SCHEMA_UNAVAILABLE, "AGGREGATE_PLAN_CHANGED");
        try {
            require(plan.windows.assign(context.window().startInclusive()).contains(context.window()), "AGGREGATE_WINDOW_CHANGED");
        } catch (VclException error) {
            return failure(ScalarValue.Status.INVALID_INPUT, error.code());
        }
        if (inputs == null) return failure(ScalarValue.Status.INVALID_INPUT, "AGGREGATE_INPUT_REQUIRED");
        if (inputs.size() > MAX_INPUTS) return failure(ScalarValue.Status.STATE_LIMIT, "AGGREGATE_INPUT_LIMIT");
        var snapshot = new ArrayList<Input>(inputs.size());
        var ids = new HashSet<UUID>();
        for (var input : inputs) {
            if (input == null) return failure(ScalarValue.Status.INVALID_INPUT, "AGGREGATE_INPUT_REQUIRED");
            if (!ids.add(input.observationId())) return failure(ScalarValue.Status.INVALID_INPUT, "AGGREGATE_INPUT_DUPLICATE");
            if (plan.reducer.equals("COUNT") != (input.value() == null)) return failure(ScalarValue.Status.INVALID_INPUT, "REDUCER_FIELD_MISMATCH");
            snapshot.add(input);
        }
        snapshot.sort(Comparator.comparing(input -> input.observationId().toString()));
        for (var input : snapshot) if (input.value() != null && input.value().isFailure()) return input.value();
        long count = 0;
        BigDecimal sum = BigDecimal.ZERO;
        JsonNode extreme = null;
        var evidence = new TreeSet<UUID>(Comparator.comparing(UUID::toString));
        boolean unknown = false;
        try {
            for (var input : snapshot) {
                VclWindowPlan.micros(input.eventTime());
                JsonNode literal = plan.reducer.equals("COUNT") ? null : validate(input.value(), plan.inputType);
                if (!context.window().contains(input.eventTime())) continue;
                count++;
                if (plan.reducer.equals("COUNT")) continue;
                if (literal == null) {
                    unknown = true;
                    evidence.add(input.observationId());
                    evidence.addAll(input.value().evidence());
                    if (evidence.size() > 128) return failure(ScalarValue.Status.STATE_LIMIT, "EVIDENCE_LIMIT");
                    continue;
                }
                if (Set.of("SUM", "AVG").contains(plan.reducer)) sum = sum.add(new BigDecimal(literal.path("value").asText()));
                else if (extreme == null || (plan.reducer.equals("MIN") ? compare(plan.inputType, literal, extreme) < 0
                        : compare(plan.inputType, literal, extreme) > 0)) extreme = literal;
            }
        } catch (VclException error) {
            return failure(ScalarValue.Status.INVALID_INPUT, error.code());
        }
        if (unknown) return ScalarValue.unknown("AGGREGATE_INPUT_UNKNOWN", List.copyOf(evidence));
        if (plan.reducer.equals("COUNT")) return number(plan.outputType, Long.toString(count));
        if (count == 0 && !plan.reducer.equals("SUM")) return ScalarValue.unknown("AGGREGATE_EMPTY", List.of());
        if (extreme != null) return ScalarValue.known(extreme);
        try {
            if (plan.reducer.equals("AVG")) {
                // DURATION retains exact integral microseconds; decimal rounding cannot invent elapsed time.
                sum = plan.outputType.kind() == VclType.Kind.DURATION
                        ? sum.divide(BigDecimal.valueOf(count))
                        : sum.divide(BigDecimal.valueOf(count), plan.decimalPolicy.scale(), plan.decimalPolicy.mode());
            }
            String result = plan.outputType.kind() == VclType.Kind.DECIMAL
                    ? plan.decimalPolicy.apply(sum).toPlainString() : Long.toString(sum.longValueExact());
            return number(plan.outputType, result);
        } catch (ArithmeticException error) {
            return failure(ScalarValue.Status.OPERATOR_ERROR, "ARITHMETIC_ERROR");
        }
    }

    private static JsonNode validate(ScalarValue value, VclType type) {
        if (value.status() == ScalarValue.Status.UNKNOWN) return null;
        var literal = PARSER.literal(value.literal().toString());
        if (literal.path("type").asText().equals("NULL")) {
            require(type.nullable(), "NULL_NOT_ALLOWED");
            return null;
        }
        type.compatible(VclType.from(literal), "");
        return literal;
    }

    private static int compare(VclType type, JsonNode a, JsonNode b) {
        String left = a.path("value").asText();
        String right = b.path("value").asText();
        return switch (type.kind()) {
            case INT64, DECIMAL, DURATION -> new BigDecimal(left).compareTo(new BigDecimal(right));
            case DATE -> LocalDate.parse(left).compareTo(LocalDate.parse(right));
            case INSTANT -> Instant.parse(left).compareTo(Instant.parse(right));
            case TEXT -> compareText(left, right);
            default -> throw new VclException("TYPE_NOT_ORDERED", "");
        };
    }

    private static int compareText(String a, String b) {
        var left = a.codePoints().iterator();
        var right = b.codePoints().iterator();
        while (left.hasNext() && right.hasNext()) {
            int compared = Integer.compare(left.nextInt(), right.nextInt());
            if (compared != 0) return compared;
        }
        return Boolean.compare(left.hasNext(), right.hasNext());
    }

    private static ScalarValue number(VclType type, String value) {
        var literal = JsonNodeFactory.instance.objectNode().put("type", type.kind().name()).put("value", value);
        if (type.numeric() && !type.unit().equals("1")) literal.put("unit", type.unit());
        return ScalarValue.known(literal);
    }
    private static ScalarValue failure(ScalarValue.Status status, String reason) { return ScalarValue.failure(status, reason); }
    private static void require(boolean condition, String code) { if (!condition) throw new VclException(code, ""); }
}
