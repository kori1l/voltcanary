package com.voltcanary.analytics.vcl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voltcanary.vcl.VclCompilation;
import com.voltcanary.vcl.VclDocument;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclParser;
import com.voltcanary.vcl.VclRegistry;
import com.voltcanary.vcl.VclType;
import com.voltcanary.vcl.VclUnits;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class VclWindowAggregateKernelTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final VclParser PARSER = new VclParser();
    private static final VclWindowAggregateKernel KERNEL = new VclWindowAggregateKernel();
    private static final String SCOPE = "a".repeat(64);
    private static final ScalarDecimalPolicy EXACT = new ScalarDecimalPolicy(
        38,
        2,
        ScalarDecimalPolicy.Rounding.REJECT
    );
    private static final VclType INT = new VclType(VclType.Kind.INT64, false, "1", null);

    @ParameterizedTest
    @CsvSource({ "COUNT,3", "SUM,12", "AVG,4", "MIN,-2", "MAX,9" })
    void allReducersUseTypedExactBatch(String reducer, String expected) {
        var plan = plan(reducer, reducer.equals("COUNT") ? null : INT, EXACT);
        var inputs = new ArrayList<VclWindowAggregateKernel.Input>();
        int i = 0;
        for (int value : new int[] { -2, 5, 9 })
            inputs.add(input(++i, i, reducer.equals("COUNT") ? null : number(INT, "" + value)));
        var actual = evaluate(plan, inputs);
        assertEquals(ScalarValue.Status.OK, actual.status());
        assertEquals(expected, actual.literal().path("value").asText());
        assertEquals(
            reducer.equals("AVG") ? "DECIMAL" : "INT64",
            actual.literal().path("type").asText()
        );
    }

    @Test
    void boundariesAndPreEpochMembershipUseMicroseconds() {
        var plan = plan("COUNT", null, EXACT);
        var window = plan.windows().assign(Instant.EPOCH.minusNanos(1000)).getFirst();
        var context = context(plan, window);
        var inputs = List.of(
            input(1, -11, null),
            input(2, -10, null),
            input(3, -1, null),
            input(4, 0, null)
        );
        assertEquals(
            "2",
            KERNEL.evaluate(plan, context, inputs, SCOPE).literal().path("value").asText()
        );
    }

    @ParameterizedTest
    @CsvSource({
        "COUNT,0",
        "SUM,0",
        "MIN,AGGREGATE_EMPTY",
        "MAX,AGGREGATE_EMPTY",
        "AVG,AGGREGATE_EMPTY",
    })
    void emptyBatchHasExplicitArithmeticIdentityOrUnknown(String reducer, String expected) {
        var actual = evaluate(
            plan(reducer, reducer.equals("COUNT") ? null : INT, EXACT),
            List.of()
        );
        assertEquals(
            expected,
            actual.status() == ScalarValue.Status.OK
                ? actual.literal().path("value").asText()
                : actual.reason()
        );
    }

    @Test
    void arbitraryOrderAndCancellationDoNotCauseIntermediateLongOverflow() {
        var plan = plan("SUM", INT, EXACT);
        var inputs = new ArrayList<>(
            List.of(
                input(1, 1, number(INT, "9223372036854775807")),
                input(2, 2, number(INT, "1")),
                input(3, 3, number(INT, "-1"))
            )
        );
        assertEquals(
            "9223372036854775807",
            evaluate(plan, inputs).literal().path("value").asText()
        );
        Collections.reverse(inputs);
        assertEquals(
            "9223372036854775807",
            evaluate(plan, inputs).literal().path("value").asText()
        );
        assertEquals("ARITHMETIC_ERROR", evaluate(plan, inputs.subList(1, 3)).reason());
    }

    @Test
    void randomizedReducersMatchIndependentLongOracle() {
        var random = new Random(71203);
        for (int iteration = 0; iteration < 60; iteration++) {
            long total = 0;
            long min = Long.MAX_VALUE;
            long max = Long.MIN_VALUE;
            var inputs = new ArrayList<VclWindowAggregateKernel.Input>();
            for (int i = 0; i < 100; i++) {
                long value = random.nextInt(2001) - 1000;
                int time = random.nextInt(20) - 5;
                inputs.add(input(i + 1, time, number(INT, "" + value)));
                if (time >= 0 && time < 10) {
                    total += value;
                    min = Math.min(min, value);
                    max = Math.max(max, value);
                }
            }
            Collections.shuffle(inputs, random);
            assertEquals(
                "" + total,
                evaluate(plan("SUM", INT, EXACT), inputs)
                    .literal()
                    .path("value")
                    .asText()
            );
            assertEquals(
                "" + min,
                evaluate(plan("MIN", INT, EXACT), inputs)
                    .literal()
                    .path("value")
                    .asText()
            );
            assertEquals(
                "" + max,
                evaluate(plan("MAX", INT, EXACT), inputs)
                    .literal()
                    .path("value")
                    .asText()
            );
        }
    }

    @Test
    void decimalsKeepUnitsAndRoundOnlyFinalReduction() {
        var type = new VclType(VclType.Kind.DECIMAL, false, "V", null);
        var half = new ScalarDecimalPolicy(38, 2, ScalarDecimalPolicy.Rounding.HALF_EVEN);
        var inputs = List.of(
            input(1, 1, number(type, "0.005")),
            input(2, 2, number(type, "0.005"))
        );
        var result = evaluate(plan("SUM", type, half), inputs);
        assertEquals("0.01", result.literal().path("value").asText());
        assertEquals("V", result.literal().path("unit").asText());
        assertEquals("ARITHMETIC_ERROR", evaluate(plan("AVG", type, EXACT), inputs).reason());
        assertEquals(
            "0",
            evaluate(plan("AVG", type, half), inputs)
                .literal()
                .path("value")
                .asText()
        );
        var huge = List.of(input(1, 1, number(type, "99999999999999999999999999999999999999")));
        assertEquals("ARITHMETIC_ERROR", evaluate(plan("SUM", type, EXACT), huge).reason());
        var absolute = new VclType(VclType.Kind.DECIMAL, false, "Cel", null);
        assertThrows(VclException.class, () -> plan("SUM", absolute, EXACT));
        assertEquals(
            "20",
            evaluate(
                plan("AVG", absolute, EXACT),
                List.of(input(1, 1, number(absolute, "10")), input(2, 2, number(absolute, "30")))
            )
                .literal()
                .path("value")
                .asText()
        );
    }

    @Test
    void durationAverageNeverRoundsFractionalMicroseconds() {
        var type = new VclType(VclType.Kind.DURATION, false, "1", null);
        var plan = plan(
            "AVG",
            type,
            new ScalarDecimalPolicy(38, 2, ScalarDecimalPolicy.Rounding.HALF_EVEN)
        );
        assertEquals(
            "ARITHMETIC_ERROR",
            evaluate(
                plan,
                List.of(input(1, 1, number(type, "1")), input(2, 2, number(type, "2")))
            ).reason()
        );
        assertEquals(
            "2",
            evaluate(plan, List.of(input(1, 1, number(type, "1")), input(2, 2, number(type, "3"))))
                .literal()
                .path("value")
                .asText()
        );
    }

    @ParameterizedTest
    @CsvSource({
        "TEXT,10,2,10",
        "DATE,2024-02-29,2024-03-01,2024-02-29",
        "INSTANT,1969-12-31T23:59:59.999999Z,1970-01-01T00:00:00Z,1969-12-31T23:59:59.999999Z",
    })
    void minimumPreservesOrderedType(String kind, String a, String b, String expected) {
        var type = new VclType(VclType.Kind.valueOf(kind), false, "1", null);
        var result = evaluate(
            plan("MIN", type, EXACT),
            List.of(input(1, 1, number(type, a)), input(2, 2, number(type, b)))
        );
        assertEquals(kind, result.literal().path("type").asText());
        assertEquals(expected, result.literal().path("value").asText());
    }

    @Test
    void textOrderingUsesUnicodeCodePointsRatherThanUtf16Units() {
        var type = new VclType(VclType.Kind.TEXT, false, "1", null);
        assertEquals(
            "\uE000",
            evaluate(
                plan("MIN", type, EXACT),
                List.of(input(1, 1, number(type, "\uE000")), input(2, 2, number(type, "😀")))
            )
                .literal()
                .path("value")
                .asText()
        );
    }

    @Test
    void unknownAndNullableValuesAreNeverSilentlySkipped() {
        var nullable = new VclType(VclType.Kind.INT64, true, "1", null);
        var plan = plan("SUM", nullable, EXACT);
        var unknown = ScalarValue.unknown("FIELD_MISSING", List.of(new UUID(0, 30)));
        var actual = evaluate(
            plan,
            List.of(input(2, 2, unknown), input(1, 1, known("{\"type\":\"NULL\",\"value\":null}")))
        );
        assertEquals("AGGREGATE_INPUT_UNKNOWN", actual.reason());
        assertEquals(List.of(new UUID(0, 1), new UUID(0, 2), new UUID(0, 30)), actual.evidence());
        assertEquals(
            "0",
            evaluate(plan, List.of(input(2, 11, unknown)))
                .literal()
                .path("value")
                .asText()
        );
        assertEquals(
            "NULL_NOT_ALLOWED",
            evaluate(
                plan("SUM", INT, EXACT),
                List.of(input(1, 1, known("{\"type\":\"NULL\",\"value\":null}")))
            ).reason()
        );
    }

    @ParameterizedTest
    @ValueSource(strings = { "COUNT", "SUM", "MIN", "MAX", "AVG" })
    void identityAndBatchLimitsRejectBeforeReduction(String reducer) {
        var plan = plan(reducer, reducer.equals("COUNT") ? null : INT, EXACT);
        var input = input(1, 1, reducer.equals("COUNT") ? null : number(INT, "1"));
        assertEquals("AGGREGATE_INPUT_DUPLICATE", evaluate(plan, List.of(input, input)).reason());
        assertEquals(
            "AGGREGATE_INPUT_LIMIT",
            evaluate(plan, Collections.nCopies(4097, input)).reason()
        );
        var inputs = new ArrayList<VclWindowAggregateKernel.Input>();
        for (int i = 1; i <= 4096; i++) inputs.add(input(i, 1, input.value()));
        assertEquals(ScalarValue.Status.OK, evaluate(plan, inputs).status());
    }

    @Test
    void invalidTimeTypeUnitsAndFailureOutsideWindowStillFailClosed() {
        var plan = plan("SUM", INT, EXACT);
        assertEquals(
            "WINDOW_TIME_INVALID",
            evaluate(
                plan,
                List.of(
                    input(1, 1, number(INT, "1")),
                    new VclWindowAggregateKernel.Input(
                        new UUID(0, 2),
                        Instant.ofEpochSecond(0, 1),
                        number(INT, "1")
                    )
                )
            ).reason()
        );
        assertEquals(
            "WINDOW_TIME_INVALID",
            evaluate(
                plan,
                List.of(new VclWindowAggregateKernel.Input(new UUID(0, 1), null, number(INT, "1")))
            ).reason()
        );
        var denied = ScalarValue.failure(ScalarValue.Status.ACCESS_REVOKED, "DENIED");
        assertEquals(
            denied,
            evaluate(
                plan,
                List.of(
                    input(1, 1, ScalarValue.unknown("MISSING", List.of())),
                    input(2, 20, denied)
                )
            )
        );
        assertEquals(
            "TYPE_MISMATCH",
            evaluate(
                plan,
                List.of(input(1, 20, number(new VclType(VclType.Kind.TEXT, false, "1", null), "x")))
            ).reason()
        );
        assertEquals(
            "UNIT_MISMATCH",
            evaluate(
                plan,
                List.of(input(1, 1, number(new VclType(VclType.Kind.INT64, false, "V", null), "1")))
            ).reason()
        );
        assertEquals(
            "REDUCER_FIELD_MISMATCH",
            evaluate(plan("COUNT", null, EXACT), List.of(input(1, 1, number(INT, "1")))).reason()
        );
        assertEquals("REDUCER_FIELD_MISMATCH", evaluate(plan, List.of(input(1, 1, null))).reason());
    }

    @Test
    void evidenceOverflowDoesNotPublishPartialResult() {
        var inputs = new ArrayList<VclWindowAggregateKernel.Input>();
        for (int i = 1; i <= 129; i++) inputs.add(
            input(i, 1, ScalarValue.unknown("MISSING", List.of()))
        );
        assertEquals("EVIDENCE_LIMIT", evaluate(plan("SUM", INT, EXACT), inputs).reason());
    }

    @Test
    void scopeVersionPlanAndWindowPinsAreMandatory() {
        var plan = plan("SUM", INT, EXACT);
        var context = context(plan, plan.windows().assign(Instant.EPOCH).getFirst());
        assertEquals(
            "ACCESS_SCOPE_CHANGED",
            KERNEL.evaluate(plan, context, List.of(), "b".repeat(64)).reason()
        );
        assertEquals(
            "AGGREGATE_KERNEL_UNAVAILABLE",
            KERNEL.evaluate(
                plan,
                new VclWindowAggregateKernel.Context(
                    "other",
                    plan.digest(),
                    SCOPE,
                    context.window()
                ),
                List.of(),
                SCOPE
            ).reason()
        );
        assertEquals(
            "AGGREGATE_PLAN_CHANGED",
            KERNEL.evaluate(
                plan,
                new VclWindowAggregateKernel.Context(
                    VclWindowAggregateKernel.VERSION,
                    "b".repeat(64),
                    SCOPE,
                    context.window()
                ),
                List.of(),
                SCOPE
            ).reason()
        );
        var wrong = new VclWindowPlan.Window(Instant.EPOCH, Instant.ofEpochSecond(1));
        assertEquals(
            "AGGREGATE_WINDOW_CHANGED",
            KERNEL.evaluate(plan, context(plan, wrong), List.of(), SCOPE).reason()
        );
        assertNotEquals(plan.digest(), plan("MAX", INT, EXACT).digest());
        assertNotEquals(
            plan.digest(),
            plan(
                "SUM",
                INT,
                new ScalarDecimalPolicy(38, 3, ScalarDecimalPolicy.Rounding.REJECT)
            ).digest()
        );
        assertFalse(plan.toString().contains("valueFieldId"));
    }

    @ParameterizedTest
    @CsvSource({ "COUNT,2", "SUM,4", "AVG,2", "MIN,1", "MAX,3" })
    void actualSharedCompilerFeedsReducerWithoutASecondInterpreter(
        String reducer,
        String expected
    ) throws Exception {
        var compilation = compiledRule(
            reducer,
            JSON.readTree(
                "{\"kind\":\"FIXED\",\"sizeUs\":\"10\",\"alignment\":\"1970-01-01T00:00:00Z\"}"
            )
        );
        var plan = VclWindowAggregateKernel.Plan.compile(compilation, "aggregate", EXACT);
        var type = new VclType(VclType.Kind.DECIMAL, false, "m/s", null);
        var first = input(1, 1, reducer.equals("COUNT") ? null : number(type, "1"));
        var second = input(2, 1, reducer.equals("COUNT") ? null : number(type, "3"));
        assertEquals(
            expected,
            evaluate(plan, List.of(first, second)).literal().path("value").asText()
        );
        var corrected = input(2, 1, reducer.equals("COUNT") ? null : number(type, "5"));
        if (reducer.equals("SUM")) {
            assertEquals(
                "6",
                evaluate(plan, List.of(first, corrected)).literal().path("value").asText()
            );
            assertEquals(
                "4",
                evaluate(plan, List.of(first, second)).literal().path("value").asText()
            );
        }
    }

    @Test
    void rollingWindowsReduceEachBoundedMembershipIndependently() throws Exception {
        var compilation = compiledRule(
            "COUNT",
            JSON.readTree(
                "{\"kind\":\"ROLLING\",\"sizeUs\":\"10\",\"stepUs\":\"6\",\"alignment\":\"1970-01-01T00:00:00Z\"}"
            )
        );
        var plan = VclWindowAggregateKernel.Plan.compile(compilation, "aggregate", EXACT);
        var inputs = List.of(input(1, -4, null), input(2, 0, null), input(3, 5, null));
        var windows = plan.windows().assign(Instant.EPOCH);
        assertEquals(2, windows.size());
        assertEquals(
            "2",
            KERNEL.evaluate(plan, context(plan, windows.get(0)), inputs, SCOPE)
                .literal()
                .path("value")
                .asText()
        );
        assertEquals(
            "2",
            KERNEL.evaluate(plan, context(plan, windows.get(1)), inputs, SCOPE)
                .literal()
                .path("value")
                .asText()
        );
    }

    @ParameterizedTest
    @CsvSource({
        "DAY,2026-10-25T12:00:00Z",
        "DAY,2026-03-29T12:00:00Z",
        "MONTH,2026-10-15T12:00:00Z",
    })
    void calendarReductionHonorsDstAndLongLocalMonths(String unit, String event) throws Exception {
        var compilation = compiledRule(
            "COUNT",
            JSON.readTree(
                "{\"kind\":\"CALENDAR\",\"unit\":\"" +
                    unit +
                    "\",\"timezone\":\"Europe/Belgrade\",\"weekStartsOn\":1}"
            )
        );
        var plan = VclWindowAggregateKernel.Plan.compile(compilation, "aggregate", EXACT);
        var window = plan.windows().assign(Instant.parse(event)).getFirst();
        var inputs = List.of(
            new VclWindowAggregateKernel.Input(new UUID(0, 1), window.startInclusive(), null),
            new VclWindowAggregateKernel.Input(
                new UUID(0, 2),
                window.endExclusive().minusNanos(1000),
                null
            ),
            new VclWindowAggregateKernel.Input(new UUID(0, 3), window.endExclusive(), null)
        );
        assertEquals(
            "2",
            KERNEL.evaluate(plan, context(plan, window), inputs, SCOPE)
                .literal()
                .path("value")
                .asText()
        );
    }

    @Test
    void invalidGraphPinsAndAlteredOutputFailBeforeReduction() throws Exception {
        var compilation = compiledRule(
            "COUNT",
            JSON.readTree(
                "{\"kind\":\"FIXED\",\"sizeUs\":\"10\",\"alignment\":\"1970-01-01T00:00:00Z\"}"
            )
        );
        assertThrows(VclException.class, () ->
            VclWindowAggregateKernel.Plan.compile(compilation, "source", EXACT)
        );
        assertThrows(VclException.class, () ->
            VclWindowAggregateKernel.Plan.compile(
                compilation,
                "aggregate",
                new ScalarDecimalPolicy(38, 3, ScalarDecimalPolicy.Rounding.HALF_EVEN)
            )
        );
        var nodes = new ArrayList<>(compilation.graph());
        var node = nodes
            .stream()
            .filter(n -> n.id().equals("aggregate"))
            .findFirst()
            .orElseThrow();
        nodes.set(
            nodes.indexOf(node),
            new VclCompilation.Node(
                node.id(),
                node.method(),
                node.inputs(),
                node.config(),
                node.outputKind(),
                Map.of(),
                null
            )
        );
        var changed = new VclCompilation(
            compilation.kind(),
            compilation.canonicalDocument(),
            compilation.documentDigest(),
            compilation.semanticDigest(),
            compilation.registryVersion(),
            compilation.compilerVersion(),
            compilation.registryDigest(),
            compilation.unitRegistryDigest(),
            compilation.accessScopeDigest(),
            compilation.dependencies(),
            nodes
        );
        assertThrows(VclException.class, () ->
            VclWindowAggregateKernel.Plan.compile(changed, "aggregate", EXACT)
        );
        var badRegistry = new VclCompilation(
            compilation.kind(),
            compilation.canonicalDocument(),
            compilation.documentDigest(),
            compilation.semanticDigest(),
            compilation.registryVersion(),
            compilation.compilerVersion(),
            "f".repeat(64),
            compilation.unitRegistryDigest(),
            compilation.accessScopeDigest(),
            compilation.dependencies(),
            compilation.graph()
        );
        assertThrows(VclException.class, () ->
            VclWindowAggregateKernel.Plan.compile(badRegistry, "aggregate", EXACT)
        );
    }

    private static VclCompilation compiledRule(String reducer, JsonNode window) {
        var document = BundleFixtures.document("aggregate-test");
        ObjectNode wire;
        try {
            wire = (ObjectNode) JSON.readTree(com.voltcanary.vcl.VclCanonical.json(document));
        } catch (java.io.IOException error) {
            throw new IllegalStateException(error);
        }
        var spec = (ObjectNode) wire.path("spec");
        ((ObjectNode) spec.path("decimalPolicy")).put("scale", EXACT.scale());
        var source = java.util.stream.StreamSupport.stream(spec.path("nodes").spliterator(), false)
            .filter(node -> node.path("method").asText().equals("read_stream"))
            .findFirst()
            .orElseThrow()
            .deepCopy();
        var alert = (ObjectNode) java.util.stream.StreamSupport.stream(
            spec.path("nodes").spliterator(),
            false
        )
            .filter(node -> node.path("method").asText().equals("emit_alert"))
            .findFirst()
            .orElseThrow()
            .deepCopy();
        ((ObjectNode) alert.path("inputs")).put("decisions", "compare");
        var nodes = spec.putArray("nodes");
        nodes.add(source);
        var aggregate = nodes.addObject().put("id", "aggregate").put("method", "window_aggregate");
        aggregate.putObject("inputs").put("events", "source");
        var config = aggregate
            .putObject("config")
            .put("subjectFieldId", "00000000-0000-4000-8000-000000000030")
            .put("reducer", reducer)
            .put("measureFieldId", "00000000-0000-4000-8000-000000000099")
            .put("emit", "ON_CLOSE");
        if (reducer.equals("COUNT")) config.putNull("valueFieldId");
        else config.put("valueFieldId", "00000000-0000-4000-8000-000000000035");
        config.set("window", window);
        var compare = nodes.addObject().put("id", "compare").put("method", "compare");
        compare.putObject("inputs").put("measures", "aggregate");
        var args = compare
            .putObject("config")
            .putObject("predicate")
            .put("op", "CALL")
            .put("method", "gt")
            .putArray("args");
        args.addObject().put("op", "FIELD").put("fieldId", "00000000-0000-4000-8000-000000000099");
        var literal = args
            .addObject()
            .put("op", "CONST")
            .putObject("literal")
            .put("type", reducer.equals("COUNT") ? "INT64" : "DECIMAL")
            .put("value", "0");
        if (!reducer.equals("COUNT")) literal.put("unit", "m/s");
        nodes.add(alert);
        return BundleFixtures.COMPILER.compile(
            PARSER.parse(wire.toString()),
            BundleFixtures.METADATA
        );
    }

    private static VclWindowAggregateKernel.Plan plan(
        String reducer,
        VclType type,
        ScalarDecimalPolicy policy
    ) {
        ObjectNode config = JSON.createObjectNode()
            .put("reducer", reducer)
            .put("measureFieldId", "result");
        if (reducer.equals("COUNT")) config.putNull("valueFieldId");
        else config.put("valueFieldId", "value");
        config
            .putObject("window")
            .put("kind", "FIXED")
            .put("sizeUs", "10")
            .put("alignment", "1970-01-01T00:00:00Z");
        VclType output = reducer.equals("COUNT")
            ? INT
            : reducer.equals("AVG") && type.kind() == VclType.Kind.INT64
              ? new VclType(VclType.Kind.DECIMAL, type.nullable(), type.unit(), null)
              : type;
        var node = new VclCompilation.Node(
            "aggregate",
            "window_aggregate",
            Map.of("events", "source"),
            config,
            "MEASURE",
            Map.of("result", output),
            null
        );
        var source = new VclCompilation.Node(
            "source",
            "read_stream",
            Map.of(),
            JSON.createObjectNode(),
            "EVENT",
            type == null ? Map.of() : Map.of("value", type),
            null
        );
        var declaration = (ObjectNode) BundleFixtures.document("typed-batch").spec();
        declaration
            .putObject("decimalPolicy")
            .put("precision", policy.precision())
            .put("scale", policy.scale())
            .put("rounding", policy.rounding().name());
        var wire = JSON.createObjectNode().put("language", "VCL/1").put("kind", "RULE");
        wire.set("spec", declaration);
        var compilation = new VclCompilation(
            VclDocument.Kind.RULE,
            wire.toString(),
            "d".repeat(64),
            "e".repeat(64),
            "vcl-1-spec-2",
            "vcl-frontend-1",
            new VclRegistry(PARSER).digest(),
            VclUnits.standard().digest(),
            SCOPE,
            List.of(),
            List.of(source, node)
        );
        return VclWindowAggregateKernel.Plan.compile(compilation, "aggregate", policy);
    }

    private static VclWindowAggregateKernel.Context context(
        VclWindowAggregateKernel.Plan plan,
        VclWindowPlan.Window window
    ) {
        return new VclWindowAggregateKernel.Context(
            VclWindowAggregateKernel.VERSION,
            plan.digest(),
            SCOPE,
            window
        );
    }

    private static ScalarValue evaluate(
        VclWindowAggregateKernel.Plan plan,
        List<VclWindowAggregateKernel.Input> inputs
    ) {
        return KERNEL.evaluate(
            plan,
            context(plan, plan.windows().assign(Instant.EPOCH).getFirst()),
            inputs,
            SCOPE
        );
    }

    private static VclWindowAggregateKernel.Input input(
        int id,
        long microseconds,
        ScalarValue value
    ) {
        return new VclWindowAggregateKernel.Input(
            new UUID(0, id),
            Instant.EPOCH.plusNanos(microseconds * 1000),
            value
        );
    }

    private static ScalarValue number(VclType type, String value) {
        var literal = JSON.createObjectNode().put("type", type.kind().name()).put("value", value);
        if (type.numeric() && !type.unit().equals("1")) literal.put("unit", type.unit());
        return known(literal.toString());
    }

    private static ScalarValue known(String literal) {
        return ScalarValue.known(PARSER, literal);
    }
}
