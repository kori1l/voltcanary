package com.voltcanary.analytics.vcl;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voltcanary.analytics.vcl.VclDurationWitnessKernel.Context;
import com.voltcanary.analytics.vcl.VclDurationWitnessKernel.HoldContract;
import com.voltcanary.analytics.vcl.VclDurationWitnessKernel.Input;
import com.voltcanary.analytics.vcl.VclDurationWitnessKernel.Plan;
import com.voltcanary.vcl.VclCompilation;
import com.voltcanary.vcl.VclException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VclDurationWitnessKernelTest {
    private static final String SCOPE = BundleFixtures.SCOPE;
    private static final KeyedStateStore.Key KEY = new KeyedStateStore.Key(UUID.randomUUID(), UUID.fromString("00000000-0000-4000-8000-000000000400"), UUID.randomUUID(), "c".repeat(64), "d".repeat(64));
    private static final HoldContract HOLD = new HoldContract("bounded-sample-hold-1", 2_000_000);
    private static final VclDurationWitnessKernel KERNEL = new VclDurationWitnessKernel();

    @Test void zeroDurationRequiresAnActualEligibleTrueWitnessAndNeverVacuousSuccess() {
        var plan = observed(0, null);
        var yes = input(1, 1_000_000, truth(true));
        assertEquals(ScalarValue.Status.UNKNOWN, evaluate(plan, List.of()).value().status());
        assertEquals(ScalarValue.Status.UNKNOWN, evaluate(plan, List.of(input(2, 0, truth(false)))).value().status());
        assertEquals(ScalarValue.Status.UNKNOWN, evaluate(plan, List.of(input(3, 0, unknown()))).value().status());
        var result = evaluate(plan, List.of(yes));
        assertPositive(result);
        assertEquals(yes.observationId(), result.firstObservationId());
        assertEquals(yes.observationId(), result.lastObservationId());
        assertEquals(0, result.coveredDurationUs());
    }

    @Test void sameTimestampFalseOrUnknownDoesNotEraseAnActualZeroDurationTrueWitness() {
        var plan = observed(0, null);
        var yes = input(3, 1_000_000, truth(true));
        var batch = List.of(input(1, 1_000_000, truth(false)), input(2, 1_000_000, unknown()), yes);
        var result = evaluate(plan, batch);
        assertPositive(result);
        assertEquals(yes.observationId(), result.firstObservationId());
        assertFalse(result.unknownEvidence().isEmpty());
    }

    @Test void aSingleInstantaneousTrueCannotInventPositiveDuration() {
        var plan = observed(1, HOLD);
        var result = evaluate(plan, List.of(input(1, 0, truth(true))));
        assertEquals("WITNESS_NOT_PROVEN", result.value().reason());
        assertEquals(0, result.coveredDurationUs());
        assertNull(result.firstObservationId());
        assertEquals(0, evaluate(plan, List.of(input(1, 0, truth(true)), input(2, 0, truth(true)))).coveredDurationUs());
    }

    @ParameterizedTest
    @CsvSource({"1999999,UNKNOWN", "2000000,OK", "2000001,UNKNOWN"})
    void holdGapAndThresholdUseExactMicroseconds(long endUs, String status) {
        var result = evaluate(observed(2_000_000, HOLD), List.of(input(1, 0, truth(true)), input(2, endUs, truth(true))));
        assertEquals(ScalarValue.Status.valueOf(status), result.value().status());
        assertEquals(endUs > 2_000_000 ? 0 : endUs, result.coveredDurationUs());
    }

    @Test void contiguousHoldsAccumulateAndFalseResetsTheEpisode() {
        var plan = observed(4_000_000, HOLD);
        assertPositive(evaluate(plan, List.of(input(1, 0, truth(true)), input(2, 2_000_000, truth(true)), input(3, 4_000_000, truth(false)))));
        var result = evaluate(plan, List.of(input(1, 0, truth(true)), input(2, 2_000_000, truth(false)),
                input(3, 3_000_000, truth(true)), input(4, 5_000_000, truth(false))));
        assertEquals(ScalarValue.Status.UNKNOWN, result.value().status());
        assertEquals(2_000_000, result.coveredDurationUs());
    }

    @Test void unknownOrNullAndConflictingSameTimestampFactsBreakContinuousDuration() {
        var plan = observed(4_000_000, HOLD);
        for (var middle : List.of(unknown(), literal("{\"type\":\"NULL\",\"value\":null}"))) {
            var result = evaluate(plan, List.of(input(1, 0, truth(true)), input(2, 2_000_000, middle),
                    input(3, 3_000_000, truth(true)), input(4, 5_000_000, truth(true))));
            assertEquals(ScalarValue.Status.UNKNOWN, result.value().status());
            assertEquals(2_000_000, result.coveredDurationUs());
            assertTrue(result.unknownEvidence().contains(new UUID(0, 2)));
        }
        var conflicting = List.of(input(1, 0, truth(true)), input(2, 2_000_000, truth(true)), input(3, 2_000_000, truth(false)), input(4, 4_000_000, truth(true)));
        assertEquals(2_000_000, evaluate(plan, conflicting).coveredDurationUs());
        assertEquals(ScalarValue.Status.UNKNOWN, evaluate(plan, conflicting).value().status());
    }

    @Test void anEarlierProvenRunRemainsKnownWhenAnotherEpisodeHasAnUnknownGap() {
        var result = evaluate(observed(2_000_000, HOLD), List.of(input(1, 0, truth(true)), input(2, 2_000_000, truth(true)), input(3, 9_000_000, truth(true))));
        assertPositive(result);
        assertEquals(2_000_000, result.coveredDurationUs());
        assertEquals(new UUID(0, 1), result.firstObservationId());
        assertEquals(new UUID(0, 2), result.lastObservationId());
        assertTrue(result.unknownEvidence().containsAll(List.of(new UUID(0, 2), new UUID(0, 3))));
    }

    @Test void endExclusiveObservationOnlyClosesAPrecedingHold() {
        assertEquals(ScalarValue.Status.UNKNOWN, evaluate(observed(0, null), List.of(input(1, 10_000_000, truth(true)))).value().status());
        assertPositive(evaluate(observed(2_000_000, HOLD), List.of(input(1, 8_000_000, truth(true)), input(2, 10_000_000, truth(false)))));
    }

    @Test void noExtrapolationOrSampleCountingBeyondTheLastActualEndpoint() {
        var batch = List.of(input(1, 0, truth(true)), input(2, 1, truth(true)), input(3, 2, truth(true)), input(4, 3, truth(true)));
        var result = evaluate(observed(1_000_000, HOLD), batch);
        assertEquals(ScalarValue.Status.UNKNOWN, result.value().status());
        assertEquals(3, result.coveredDurationUs());
    }

    @Test void batchOrderAndSameTimestampOrderingAreDeterministic() {
        var plan = observed(2_000_000, HOLD);
        var batch = new ArrayList<>(List.of(input(1, 0, truth(true)), input(2, 2_000_000, truth(true)), input(3, 2_000_000, truth(false)), input(4, 4_000_000, truth(true))));
        var expected = evaluate(plan, batch);
        for (int i = 0; i < 30; i++) {
            Collections.shuffle(batch, new java.util.Random(i));
            var actual = evaluate(plan, batch);
            assertEquals(expected.coveredDurationUs(), actual.coveredDurationUs());
            assertEquals(expected.firstObservationId(), actual.firstObservationId());
            assertEquals(expected.lastObservationId(), actual.lastObservationId());
            assertEquals(expected.unknownEvidence(), actual.unknownEvidence());
        }
    }

    @Test void inputErrorsCannotBeHiddenByAPositiveWitnessOrFalsePredicate() {
        var plan = observed(0, null);
        var fail = ScalarValue.failure(ScalarValue.Status.SCHEMA_UNAVAILABLE, "OWNER_UNAVAILABLE");
        assertEquals(fail, evaluate(plan, List.of(input(1, 0, truth(true)), input(2, 1, fail))).value());
        var wrongType = literal("{\"type\":\"TEXT\",\"value\":\"private value\"}");
        assertEquals("TYPE_MISMATCH", evaluate(plan, List.of(input(1, 0, truth(true)), input(2, 1, wrongType))).value().reason());
        assertEquals("WITNESS_INPUT_DUPLICATE", evaluate(plan, List.of(input(1, 0, truth(true)), input(1, 1, truth(true)))).value().reason());
        assertEquals("WITNESS_INPUT_REQUIRED", evaluate(plan, null).value().reason());
        assertEquals("WITNESS_INPUT_REQUIRED", evaluate(plan, java.util.Arrays.asList((Input) null)).value().reason());
    }

    @Test void everyAccountRuleDeploymentSubjectAndOccurrencePartMustMatch() {
        var plan = observed(0, null);
        for (var foreign : List.of(
                new KeyedStateStore.Key(UUID.randomUUID(), KEY.ruleId(), KEY.deploymentEpoch(), KEY.subjectDigest(), KEY.occurrenceDigest()),
                new KeyedStateStore.Key(KEY.accountId(), UUID.randomUUID(), KEY.deploymentEpoch(), KEY.subjectDigest(), KEY.occurrenceDigest()),
                new KeyedStateStore.Key(KEY.accountId(), KEY.ruleId(), UUID.randomUUID(), KEY.subjectDigest(), KEY.occurrenceDigest()),
                new KeyedStateStore.Key(KEY.accountId(), KEY.ruleId(), KEY.deploymentEpoch(), "e".repeat(64), KEY.occurrenceDigest()),
                new KeyedStateStore.Key(KEY.accountId(), KEY.ruleId(), KEY.deploymentEpoch(), KEY.subjectDigest(), "e".repeat(64)))) {
            var result = evaluate(plan, List.of(new Input(UUID.randomUUID(), foreign, Instant.EPOCH, truth(true))));
            assertEquals("WITNESS_PARTITION_CHANGED", result.value().reason());
        }
    }

    @Test void scopeVersionAndPlanFencesFailBeforeAnyWitnessCanBeReturned() {
        var plan = observed(0, null);
        var inputs = List.of(input(1, 0, truth(true)));
        var context = context(plan);
        assertEquals(ScalarValue.Status.ACCESS_REVOKED, KERNEL.evaluate(plan, context, inputs, "e".repeat(64)).value().status());
        assertEquals("ACCESS_SCOPE_CHANGED", KERNEL.evaluate(plan, new Context(VclDurationWitnessKernel.VERSION, plan.digest(), "e".repeat(64), KEY, context.window()), inputs, SCOPE).value().reason());
        assertEquals("WITNESS_KERNEL_UNAVAILABLE", KERNEL.evaluate(plan, new Context("unavailable", plan.digest(), SCOPE, KEY, context.window()), inputs, SCOPE).value().reason());
        assertEquals("WITNESS_PLAN_CHANGED", KERNEL.evaluate(plan, new Context(VclDurationWitnessKernel.VERSION, "e".repeat(64), SCOPE, KEY, context.window()), inputs, SCOPE).value().reason());
        var foreignRule = new KeyedStateStore.Key(KEY.accountId(), UUID.randomUUID(), KEY.deploymentEpoch(), KEY.subjectDigest(), KEY.occurrenceDigest());
        assertEquals("WITNESS_RULE_CHANGED", KERNEL.evaluate(plan, new Context(VclDurationWitnessKernel.VERSION, plan.digest(), SCOPE, foreignRule, context.window()), inputs, SCOPE).value().reason());
    }

    @Test void inputsAndUnknownEvidenceHaveIndependentBudgets() {
        var plan = observed(0, null);
        assertEquals("WITNESS_INPUT_LIMIT", evaluate(plan, Collections.nCopies(4097, input(1, 0, truth(true)))).value().reason());
        var evidence = java.util.stream.IntStream.range(10, 138).mapToObj(i -> new UUID(0, i)).toList();
        var value = ScalarValue.unknown("MISSING", evidence);
        assertEquals("EVIDENCE_LIMIT", evaluate(plan, List.of(input(1, 0, truth(true)), input(2, 1, value))).value().reason());
    }

    @Test void timeRangeAndMicrosecondPrecisionAreNeverSilentlyClipped() {
        var plan = observed(0, null);
        assertEquals("WITNESS_OUTSIDE_WINDOW", evaluate(plan, List.of(input(1, -1, truth(true)))).value().reason());
        assertEquals("WITNESS_OUTSIDE_WINDOW", evaluate(plan, List.of(input(1, 10_000_001, truth(true)))).value().reason());
        var submicro = new Input(UUID.randomUUID(), KEY, Instant.EPOCH.plusNanos(1), truth(true));
        assertEquals("WINDOW_TIME_INVALID", evaluate(plan, List.of(submicro)).value().reason());
    }

    @Test void positiveDurationRequiresAnExplicitHoldWithinTheRootAndOperatorGap() {
        var compilation = observedCompilation(1);
        code("WITNESS_HOLD_REQUIRED", () -> Plan.compile(compilation, "action", null));
        code("WITNESS_HOLD_EXCEEDS_GAP", () -> Plan.compile(compilation, "action", new HoldContract("bounded-sample-hold-1", 2_000_001)));
        assertThrows(IllegalArgumentException.class, () -> new HoldContract("unapproved", 1));
        assertThrows(IllegalArgumentException.class, () -> new HoldContract("bounded-sample-hold-1", 0));
        assertNotEquals(observed(1, HOLD).digest(), observed(1, new HoldContract("bounded-sample-hold-1", 1_000_000)).digest());
        code("WITNESS_OPERATOR_REQUIRED", () -> Plan.compile(compilation, "source", HOLD));
    }

    @Test void planCannotTrustAnAlteredIrConfigOrDocumentDigest() {
        var compilation = observedCompilation(1);
        var nodes = new ArrayList<>(compilation.graph());
        for (int i = 0; i < nodes.size(); i++) {
            var original = nodes.get(i);
            if (original.id().equals("action")) {
                var changed = original.config();
                ((ObjectNode) changed).put("minimumDurationUs", "0");
                nodes.set(i, new VclCompilation.Node(original.id(), original.method(), original.inputs(), changed, original.outputKind(), original.fields(), original.sessionId()));
            }
        }
        var altered = new VclCompilation(compilation.kind(), compilation.canonicalDocument(), compilation.documentDigest(), compilation.semanticDigest(),
                compilation.registryVersion(), compilation.compilerVersion(), compilation.registryDigest(), compilation.unitRegistryDigest(), SCOPE, compilation.dependencies(), nodes);
        code("WITNESS_DOCUMENT_CHANGED", () -> Plan.compile(altered, "action", HOLD));
    }

    @Test void sustainedPlanSharesTheSameMeasuredDurationKernel() {
        var example = java.util.stream.StreamSupport.stream(BundleFixtures.EXAMPLES.path("documents").spliterator(), false)
                .filter(doc -> java.util.stream.StreamSupport.stream(doc.at("/spec/nodes").spliterator(), false)
                        .anyMatch(node -> node.path("method").asText().equals("sustained"))).findFirst().orElseThrow().deepCopy();
        String nodeId = null;
        for (var node : example.at("/spec/nodes")) {
            if (node.path("method").asText().equals("sustained")) {
                nodeId = node.path("id").asText();
                ((ObjectNode) node.path("config")).put("minimumDurationUs", "2000000").put("maxGapUs", "2000000");
            }
        }
        var compiled = BundleFixtures.COMPILER.compile(BundleFixtures.PARSER.parse(example.toString()), BundleFixtures.METADATA);
        var plan = Plan.compile(compiled, nodeId, HOLD);
        var partition = context(plan).partition();
        assertPositive(evaluate(plan, List.of(new Input(new UUID(0, 1), partition, Instant.EPOCH, truth(true)),
                new Input(new UUID(0, 2), partition, Instant.EPOCH.plusSeconds(2), truth(false)))));
    }

    @Test void independentMicrotickOracleChecksRunsAcrossRandomTrueFalseUnknownAndGapInputs() {
        var plans = new ArrayList<Plan>();
        for (int minimum = 1; minimum <= 12; minimum++) plans.add(observed(minimum, new HoldContract("bounded-sample-hold-1", 2)));
        var random = new java.util.Random(628457);
        for (int iteration = 0; iteration < 200; iteration++) {
            int required = 1 + random.nextInt(12);
            var batch = new ArrayList<Input>();
            var covered = new boolean[200];
            int time = 0, previousTime = 0, previousTruth = -1;
            for (int i = 0; i < 30; i++) {
                int state = random.nextInt(3);
                if (i > 0) {
                    time += 1 + random.nextInt(4);
                    if (time - previousTime <= 2 && previousTruth == 1) {
                        for (int tick = previousTime; tick < time; tick++) covered[tick] = true;
                    }
                }
                batch.add(input(i + 1, time, state == 2 ? unknown() : truth(state == 1)));
                previousTime = time;
                previousTruth = state;
            }
            int longest = 0, consecutive = 0;
            for (boolean tick : covered) {
                consecutive = tick ? consecutive + 1 : 0;
                longest = Math.max(longest, consecutive);
            }
            Collections.shuffle(batch, random);
            var result = evaluate(plans.get(required - 1), batch);
            assertEquals(longest, result.coveredDurationUs());
            assertEquals(longest >= required ? ScalarValue.Status.OK : ScalarValue.Status.UNKNOWN, result.value().status());
        }
    }

    @Test void preEpochIntervalsAndUnreachableMaximumThresholdPreserveExactDuration() {
        var plan = observed(2_000_000, HOLD);
        var window = new VclWindowPlan.Window(Instant.EPOCH.minusSeconds(4), Instant.EPOCH.plusSeconds(4));
        var context = new Context(VclDurationWitnessKernel.VERSION, plan.digest(), SCOPE, KEY, window);
        var result = KERNEL.evaluate(plan, context, List.of(input(1, -2_000_000, truth(true)), input(2, 0, truth(false))), SCOPE);
        assertPositive(result);
        assertEquals(2_000_000, result.coveredDurationUs());
        var maximum = evaluate(observed(Long.MAX_VALUE, HOLD), List.of(input(1, 0, truth(true)), input(2, 2_000_000, truth(false))));
        assertEquals(ScalarValue.Status.UNKNOWN, maximum.value().status());
        assertEquals(2_000_000, maximum.coveredDurationUs());
    }

    private static VclCompilation observedCompilation(long minimumUs) {
        var document = BundleFixtures.document("witness").json();
        for (var node : document.at("/spec/nodes")) if (node.path("id").asText().equals("action")) ((ObjectNode) node.path("config")).put("minimumDurationUs", Long.toString(minimumUs));
        return BundleFixtures.COMPILER.compile(BundleFixtures.PARSER.parse(document.toString()), BundleFixtures.METADATA);
    }
    private static Plan observed(long minimumUs, HoldContract hold) { return Plan.compile(observedCompilation(minimumUs), "action", hold); }
    private static Context context(Plan plan) { return new Context(VclDurationWitnessKernel.VERSION, plan.digest(), SCOPE,
            new KeyedStateStore.Key(KEY.accountId(), plan.ruleId(), KEY.deploymentEpoch(), KEY.subjectDigest(), KEY.occurrenceDigest()),
            new VclWindowPlan.Window(Instant.EPOCH, Instant.EPOCH.plusSeconds(10))); }
    private static VclDurationWitnessKernel.Result evaluate(Plan plan, List<Input> inputs) { return KERNEL.evaluate(plan, context(plan), inputs, SCOPE); }
    private static Input input(long id, long us, ScalarValue truth) { return new Input(new UUID(0, id), KEY, Instant.EPOCH.plusNanos(us * 1000), truth); }
    private static ScalarValue literal(String value) { return ScalarValue.known(BundleFixtures.PARSER, value); }
    private static ScalarValue truth(boolean value) { return literal("{\"type\":\"BOOLEAN\",\"value\":" + value + "}"); }
    private static ScalarValue unknown() { return ScalarValue.unknown("MISSING", List.of()); }
    private static void assertPositive(VclDurationWitnessKernel.Result result) { assertEquals(ScalarValue.Status.OK, result.value().status()); assertTrue(result.value().literal().path("value").asBoolean()); }
    private static void code(String expected, org.junit.jupiter.api.function.Executable action) { assertEquals(expected, assertThrows(VclException.class, action).code()); }
}
