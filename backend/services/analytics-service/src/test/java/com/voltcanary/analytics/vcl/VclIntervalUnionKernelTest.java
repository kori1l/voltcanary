package com.voltcanary.analytics.vcl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.vcl.VclCompilation;
import com.voltcanary.vcl.VclMetadata;
import com.voltcanary.vcl.VclParser;
import com.voltcanary.vcl.VclRegistry;
import com.voltcanary.vcl.VclScalarCompiler;
import com.voltcanary.vcl.VclType;
import com.voltcanary.vcl.VclUnits;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VclIntervalUnionKernelTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final VclParser PARSER = new VclParser();
    private static final VclIntervalUnionKernel KERNEL = new VclIntervalUnionKernel();
    private static final String SCOPE = "a".repeat(64);
    private static final ScalarValue TRUE = known("{\"type\":\"BOOLEAN\",\"value\":true}");
    private static final ScalarValue FALSE = known("{\"type\":\"BOOLEAN\",\"value\":false}");
    private static final ScalarValue NULL = known("{\"type\":\"NULL\",\"value\":null}");

    @Test
    void acceptanceWorkUnionAndApprovedBreakCorrectionUseActualElapsedTime() {
        var context = context("2026-11-04T00:00:00Z", "2026-11-05T00:00:00Z");
        var work1 = fact(1, "2026-11-04T08:00:00Z", "2026-11-04T12:00:00Z", true, false);
        var work2 = fact(2, "2026-11-04T11:00:00Z", "2026-11-04T17:00:00Z", true, false);
        var pause = fact(3, "2026-11-04T12:00:00Z", "2026-11-04T13:00:00Z", false, true);
        var original = List.of(work1, work2, pause);
        duration(9L * 3_600_000_000L, evaluate(context, List.of(work1, work2)));
        duration(8L * 3_600_000_000L, evaluate(context, original));
        var corrected = fact(3, "2026-11-04T12:00:00Z", "2026-11-04T12:30:00Z", false, true);
        duration(30_600_000_000L, evaluate(context, List.of(work1, work2, corrected)));
        duration(8L * 3_600_000_000L, evaluate(context, original));
    }

    @Test
    void overlapTouchingNestedAndDuplicatedBoundsNeverDoubleCount() {
        var inputs = List.of(microFact(1, 0, 10, true, false), microFact(2, 0, 10, true, false),
                microFact(3, 2, 8, true, false), microFact(4, 10, 20, true, false),
                microFact(5, 3, 5, false, true), microFact(6, 4, 7, false, true),
                microFact(7, 7, 9, false, true), microFact(8, 2, 2, true, true));
        duration(14, evaluate(microContext(0, 20), inputs));
        var reversed = new ArrayList<>(inputs);
        Collections.reverse(reversed);
        duration(14, evaluate(microContext(0, 20), reversed));
    }

    @Test
    void clippingAndExclusionsApplyOnlyInsideWorkAndHalfOpenWindow() {
        var inputs = List.of(microFact(1, -100, 2, true, false), microFact(2, 5, 100, true, false),
                microFact(3, -100, 1, false, true), microFact(4, 2, 5, false, true),
                microFact(5, 7, 100, false, true), microFact(6, 10, 20, true, false),
                microFact(7, -100, 0, true, false));
        duration(3, evaluate(microContext(0, 10), inputs));
        duration(0, evaluate(microContext(0, 10), List.of(microFact(1, -10, 20, false, true))));
        duration(0, evaluate(microContext(0, 10), List.of(microFact(1, -10, 20, true, true))));
        duration(0, evaluate(microContext(0, 10), List.of()));
    }

    @Test
    void independentRandomizedDiscreteOccupancyOracleMatchesUnionDifference() {
        var random = new Random(7331);
        for (int run = 0; run < 100; run++) {
            boolean[] work = new boolean[40];
            boolean[] pauses = new boolean[40];
            var inputs = new ArrayList<VclIntervalUnionKernel.Input>();
            for (int n = 0; n < 20; n++) {
                int start = random.nextInt(61) - 30;
                int end = start + random.nextInt(25);
                boolean include = random.nextBoolean();
                boolean exclude = random.nextBoolean();
                inputs.add(microFact(n + 1, start, end, include, exclude));
                for (int cell = -20; cell < 20; cell++) {
                    if (cell >= start && cell < end) {
                        work[cell + 20] |= include;
                        pauses[cell + 20] |= exclude;
                    }
                }
            }
            long expected = 0;
            for (int cell = 0; cell < 40; cell++) if (work[cell] && !pauses[cell]) expected++;
            Collections.shuffle(inputs, random);
            duration(expected, evaluate(microContext(-20, 20), inputs));
        }
    }

    @ParameterizedTest
    @CsvSource({
        "Europe/Belgrade,DAY,2026-03-29T12:00:00Z,23",
        "Europe/Belgrade,DAY,2026-10-25T12:00:00Z,25",
        "Europe/Belgrade,MONTH,2026-10-15T12:00:00Z,745",
        "Europe/Belgrade,MONTH,2026-03-15T12:00:00Z,743",
        "UTC,MONTH,2024-02-15T12:00:00Z,696"
    })
    void calendarAssignmentAndUnionShareExactDstMonthBoundaries(String zone, String unit, String event, long hours) {
        var config = JSON.createObjectNode();
        config.set("window", JSON.createObjectNode().put("kind", "CALENDAR").put("unit", unit).put("timezone", zone).put("weekStartsOn", 1));
        var node = new VclCompilation.Node("measure", "window_aggregate", Map.of(), config, "MEASURE", Map.of(), null);
        var plan = VclWindowPlan.compile(node);
        var window = plan.assign(Instant.parse(event)).getFirst();
        var context = new VclIntervalUnionKernel.Context(VclIntervalUnionKernel.VERSION, SCOPE, window);
        var input = new VclIntervalUnionKernel.Input(id(1), interval(window.startInclusive().toString(), window.endExclusive().toString()), TRUE, FALSE);
        duration(hours * 3_600_000_000L, evaluate(context, List.of(input)));
        var crossing = fact(2, window.startInclusive().minusSeconds(3600).toString(), window.startInclusive().plusSeconds(3600).toString(), false, true);
        duration((hours - 1) * 3_600_000_000L, evaluate(context, List.of(input, crossing)));
    }

    @Test
    void canonicalUtcIntervalsPreserveNegativeEpochMicroseconds() {
        var context = context("1969-12-31T23:59:59.999990Z", "1970-01-01T00:00:00.000010Z");
        var input = fact(1, "1969-12-31T23:59:59.999999Z", "1970-01-01T00:00:00.000001Z", true, false);
        duration(2, evaluate(context, List.of(input)));
    }

    @Test
    void unknownBreakNeverBecomesZeroAndFalseRowsMayRemainUnselected() {
        var unknown = ScalarValue.unknown("BREAK_UNAVAILABLE", List.of(id(99)));
        var input = new VclIntervalUnionKernel.Input(id(2), unknown, FALSE, TRUE);
        var result = evaluate(microContext(0, 20), List.of(microFact(1, 0, 20, true, false), input));
        assertEquals(ScalarValue.Status.UNKNOWN, result.status());
        assertEquals(List.of(id(2), id(99)), result.evidence());
        assertEquals("INTERVAL_INPUT_UNKNOWN", result.reason());
        duration(0, evaluate(microContext(0, 20), List.of(new VclIntervalUnionKernel.Input(id(2), unknown, FALSE, FALSE))));
        assertEquals(ScalarValue.Status.UNKNOWN, evaluate(microContext(0, 20), List.of(new VclIntervalUnionKernel.Input(id(2), NULL, TRUE, FALSE))).status());
    }

    @ParameterizedTest
    @ValueSource(strings = {"INCLUDE", "EXCLUDE", "NULL_INCLUDE", "NULL_EXCLUDE", "OPEN"})
    void unresolvedMembershipAndOpenEndsRemainUnknown(String kind) {
        var unknown = ScalarValue.unknown("FIELD_MISSING", List.of(id(99)));
        var input = new VclIntervalUnionKernel.Input(id(1), kind.equals("OPEN") ? interval(Instant.EPOCH.toString(), null) : interval(Instant.EPOCH.toString(), Instant.EPOCH.plusSeconds(1).toString()),
                kind.equals("INCLUDE") ? unknown : kind.equals("NULL_INCLUDE") ? NULL : TRUE,
                kind.equals("EXCLUDE") ? unknown : kind.equals("NULL_EXCLUDE") ? NULL : FALSE);
        assertEquals(ScalarValue.Status.UNKNOWN, evaluate(microContext(0, 10), List.of(input)).status());
    }

    @Test
    void existingErrorsAreNeverHiddenByFalsePredicatesOrEarlierUnknowns() {
        var error = ScalarValue.failure(ScalarValue.Status.ACCESS_REVOKED, "OWNER_ACCESS_REVOKED");
        var context = microContext(0, 20);
        var unknown = new VclIntervalUnionKernel.Input(id(1), ScalarValue.unknown("MISSING", List.of()), TRUE, FALSE);
        for (int field = 0; field < 3; field++) {
            var input = new VclIntervalUnionKernel.Input(id(2), field == 0 ? error : NULL, field == 1 ? error : FALSE, field == 2 ? error : FALSE);
            var result = evaluate(context, List.of(unknown, input));
            assertEquals(ScalarValue.Status.ACCESS_REVOKED, result.status());
            assertEquals("OWNER_ACCESS_REVOKED", result.reason());
        }
    }

    @Test
    void versionAndScopeFencesRunBeforeAnyInputRead() {
        var context = microContext(0, 20);
        var denied = KERNEL.evaluate(context, null, "b".repeat(64));
        assertEquals(ScalarValue.Status.ACCESS_REVOKED, denied.status());
        var old = new VclIntervalUnionKernel.Context("old-kernel", SCOPE, context.window());
        assertEquals("INTERVAL_KERNEL_UNAVAILABLE", KERNEL.evaluate(old, null, SCOPE).reason());
        assertEquals("INTERVAL_INPUT_REQUIRED", KERNEL.evaluate(context, null, SCOPE).reason());
    }

    @Test
    void duplicateObservationIdentityAndNullItemsCannotBecomeExtraWork() {
        var fact = microFact(1, 0, 1, true, false);
        assertEquals("INTERVAL_INPUT_DUPLICATE", evaluate(microContext(0, 20), List.of(fact, fact)).reason());
        assertEquals("INTERVAL_INPUT_DUPLICATE", evaluate(microContext(0, 20), List.of(fact, microFact(1, 5, 10, true, false))).reason());
        assertEquals("INTERVAL_INPUT_REQUIRED", evaluate(microContext(0, 20), java.util.Arrays.asList((VclIntervalUnionKernel.Input) null)).reason());
        assertThrows(NullPointerException.class, () -> new VclIntervalUnionKernel.Input(id(1), null, TRUE, FALSE));
    }

    @Test
    void inputAndEvidenceBudgetsAreHardFailuresWithoutPartialResults() {
        var inputs = new ArrayList<VclIntervalUnionKernel.Input>();
        for (int n = 1; n <= VclIntervalUnionKernel.MAX_INPUTS; n++) inputs.add(microFact(n, 0, 1, true, false));
        duration(1, evaluate(microContext(0, 20), inputs));
        inputs.add(microFact(VclIntervalUnionKernel.MAX_INPUTS + 1, 0, 1, true, false));
        assertEquals("INTERVAL_INPUT_LIMIT", evaluate(microContext(0, 20), inputs).reason());
        inputs.clear();
        for (int n = 1; n <= 128; n++) inputs.add(new VclIntervalUnionKernel.Input(id(n), NULL, FALSE, TRUE));
        var result = evaluate(microContext(0, 20), inputs);
        assertEquals(128, result.evidence().size());
        inputs.add(new VclIntervalUnionKernel.Input(id(129), NULL, FALSE, TRUE));
        assertEquals(ScalarValue.Status.STATE_LIMIT, evaluate(microContext(0, 20), inputs).status());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"type\":\"TEXT\",\"value\":\"private-value\"}",
        "{\"type\":\"INTERVAL\",\"value\":{\"start\":\"1970-01-01T00:00:01Z\",\"end\":\"1970-01-01T00:00:00Z\"}}",
        "{\"type\":\"INTERVAL\",\"value\":{\"start\":\"1970-01-01T00:00:00.000000001Z\",\"end\":null}}",
        "{\"type\":\"INTERVAL\",\"value\":{\"start\":\"0001-01-01T00:00:00+01:00\",\"end\":null}}",
        "{\"type\":\"INTERVAL\",\"value\":{\"start\":\"1970-01-01T02:00:00+02:00\",\"end\":null}}",
        "{\"type\":\"INTERVAL\",\"value\":{\"start\":\"1970-01-01T00:00:00Z\",\"end\":null,\"complete\":true}}"
    })
    void malformedTypedValuesFailInsteadOfBecomingUnknownOrZero(String value) throws Exception {
        var raw = ScalarValue.known(JSON.readTree(value));
        var result = evaluate(microContext(0, 20), List.of(new VclIntervalUnionKernel.Input(id(1), raw, FALSE, FALSE)));
        assertEquals(ScalarValue.Status.INVALID_INPUT, result.status());
        assertFalse(result.toString().contains("private-value"));
    }

    @Test
    void invalidPredicatesAndUnboundedContextsAreRejected() {
        var notBoolean = known("{\"type\":\"INT64\",\"value\":\"1\"}");
        var fact = new VclIntervalUnionKernel.Input(id(1), NULL, notBoolean, FALSE);
        assertEquals("TYPE_MISMATCH", evaluate(microContext(0, 20), List.of(fact)).reason());
        assertThrows(IllegalArgumentException.class, () -> new VclIntervalUnionKernel.Context(VclIntervalUnionKernel.VERSION, "bad-scope", microContext(0, 20).window()));
        assertThrows(IllegalArgumentException.class, () -> context("2026-01-01T00:00:00Z", "2026-02-03T00:00:00Z"));
        assertThrows(IllegalArgumentException.class, () -> new VclIntervalUnionKernel.Context("private value", SCOPE, microContext(0, 20).window()));
    }

    @Test
    void malformedNullLiteralsCannotBypassClosedValidation() {
        var malformed = ScalarValue.known(JSON.createObjectNode().put("type", "NULL").put("value", "private"));
        for (int field = 0; field < 3; field++) {
            var input = new VclIntervalUnionKernel.Input(id(1), field == 0 ? malformed : NULL, field == 1 ? malformed : TRUE, field == 2 ? malformed : FALSE);
            assertEquals(ScalarValue.Status.INVALID_INPUT, evaluate(microContext(0, 20), List.of(input)).status());
        }
    }

    @Test
    void scalarCompilerAndEvaluatorSupplyTheSameThreeValuedPredicates() {
        UUID includeField = id(50);
        UUID excludeField = id(51);
        var registry = new VclRegistry(PARSER);
        var units = VclUnits.standard();
        var compiler = new VclScalarCompiler(PARSER, registry, units);
        VclMetadata metadata = new VclMetadata() {
            public String accessScopeDigest() { return SCOPE; }
            public Optional<Snapshot> dataset(UUID id, long revision) { return Optional.empty(); }
            public Optional<Snapshot> binding(UUID id, long revision) { return Optional.empty(); }
            public boolean permits(Resource kind, UUID id) { return false; }
        };
        var fields = Map.of(includeField, VclType.BOOLEAN, excludeField, VclType.BOOLEAN);
        var include = compiler.compile("{\"op\":\"FIELD\",\"fieldId\":\"" + includeField + "\"}", fields, metadata);
        var exclude = compiler.compile("{\"op\":\"FIELD\",\"fieldId\":\"" + excludeField + "\"}", fields, metadata);
        var evaluator = new VclScalarEvaluator(units, registry.digest());
        var policy = new ScalarDecimalPolicy(38, 12, ScalarDecimalPolicy.Rounding.REJECT);
        var record = Map.of(includeField, TRUE, excludeField, ScalarValue.unknown("BREAK_UNKNOWN", List.of(id(99))));
        var input = new VclIntervalUnionKernel.Input(id(1), interval(Instant.EPOCH.toString(), Instant.EPOCH.plusSeconds(1).toString()),
                evaluator.evaluate(include, record, policy, SCOPE), evaluator.evaluate(exclude, record, policy, SCOPE));
        var result = evaluate(microContext(0, 20), List.of(input));
        assertEquals(ScalarValue.Status.UNKNOWN, result.status());
        assertEquals(List.of(id(1), id(99)), result.evidence());
        var denied = evaluator.evaluate(include, record, policy, "b".repeat(64));
        assertEquals(ScalarValue.Status.ACCESS_REVOKED, evaluate(microContext(0, 20), List.of(new VclIntervalUnionKernel.Input(id(1), NULL, denied, FALSE))).status());
    }

    @Test
    void boundaryZeroIntervalsAndDiagnosticsRetainNoPrivatePayloads() {
        var max = fact(1, "9999-12-31T23:59:59.999999Z", "9999-12-31T23:59:59.999999Z", true, false);
        duration(0, evaluate(microContext(0, 20), List.of(max)));
        var context = microContext(0, 20);
        assertFalse(context.toString().contains(SCOPE));
        assertFalse(max.toString().contains("9999"));
        var unknown = evaluate(context, List.of(new VclIntervalUnionKernel.Input(id(1), NULL, TRUE, FALSE)));
        assertThrows(UnsupportedOperationException.class, () -> unknown.evidence().clear());
    }

    private static ScalarValue evaluate(VclIntervalUnionKernel.Context context, List<VclIntervalUnionKernel.Input> inputs) { return KERNEL.evaluate(context, inputs, SCOPE); }
    private static void duration(long expected, ScalarValue value) {
        assertEquals(ScalarValue.Status.OK, value.status(), value.reason());
        assertEquals("DURATION", value.literal().path("type").asText());
        assertEquals(Long.toString(expected), value.literal().path("value").asText());
    }
    private static VclIntervalUnionKernel.Context context(String start, String end) {
        return new VclIntervalUnionKernel.Context(VclIntervalUnionKernel.VERSION, SCOPE, new VclWindowPlan.Window(Instant.parse(start), Instant.parse(end)));
    }
    private static VclIntervalUnionKernel.Context microContext(long start, long end) { return context(micro(start).toString(), micro(end).toString()); }
    private static VclIntervalUnionKernel.Input microFact(long id, long start, long end, boolean include, boolean exclude) { return fact(id, micro(start).toString(), micro(end).toString(), include, exclude); }
    private static VclIntervalUnionKernel.Input fact(long id, String start, String end, boolean include, boolean exclude) {
        return new VclIntervalUnionKernel.Input(id(id), interval(start, end), include ? TRUE : FALSE, exclude ? TRUE : FALSE);
    }
    private static ScalarValue interval(String start, String end) {
        var bounds = JSON.createObjectNode().put("start", start);
        if (end == null) bounds.putNull("end"); else bounds.put("end", end);
        var literal = JSON.createObjectNode().put("type", "INTERVAL");
        literal.set("value", bounds);
        return known(literal.toString());
    }
    private static ScalarValue known(String value) { return ScalarValue.known(PARSER, value); }
    private static UUID id(long id) { return new UUID(0, id); }
    private static Instant micro(long value) { return Instant.EPOCH.plusNanos(value * 1000); }
}
