package com.voltcanary.analytics.vcl;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voltcanary.vcl.VclCompilation;
import com.voltcanary.vcl.VclException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class VclTimingPlanTest {
    private static final String SCOPE = BundleFixtures.SCOPE;
    private static final VclCompilation COMPILED = BundleFixtures.compilation("timing");
    private static final VclTimingPlan PLAN = VclTimingPlan.compile(COMPILED);

    @ParameterizedTest
    @CsvSource({"-1,ELIGIBLE_TIME", "0,ELIGIBLE_TIME", "15000000,ELIGIBLE_TIME", "15000001,QUARANTINED_FUTURE"})
    void futureSkewUsesExactOwnerMicroseconds(long deltaUs, String expected) {
        assertEquals(VclTimingPlan.Admission.valueOf(expected), PLAN.classify(Instant.EPOCH.plusNanos(deltaUs * 1000), Instant.EPOCH));
    }

    @Test
    void futureClockArithmeticCannotOverflowOrWrapAtSupportedTimeLimits() {
        var min = Instant.parse("0001-01-01T00:00:00Z");
        var max = Instant.parse("9999-12-31T23:59:59.999999Z");
        assertEquals(VclTimingPlan.Admission.QUARANTINED_FUTURE, PLAN.classify(max, min));
        assertEquals(VclTimingPlan.Admission.ELIGIBLE_TIME, PLAN.classify(min, max));
        assertThrows(VclException.class, () -> PLAN.classify(Instant.EPOCH.plusNanos(1), Instant.EPOCH));
        assertThrows(VclException.class, () -> PLAN.classify(Instant.EPOCH, null));
    }

    @Test
    void minimumOfSourcesAndReferenceProgressSubtractsRootLatenessExactlyOnce() {
        var inputs = inputs(Instant.parse("2026-11-04T12:00:30Z"));
        inputs.set(0, new VclTimingPlan.Progress(inputs.get(0).input(), Instant.parse("2026-11-04T12:00:20Z"), Instant.parse("2026-11-04T12:00:20Z")));
        assertEquals("2026-11-04T12:00:10Z", watermark(inputs).literal().path("value").asText());
        Collections.reverse(inputs);
        assertEquals("2026-11-04T12:00:10Z", watermark(inputs).literal().path("value").asText());
        assertTrue(PLAN.requiredInputs().stream().anyMatch(input -> input.kind() == com.voltcanary.vcl.VclMetadata.Resource.DATASET));
        assertTrue(PLAN.requiredInputs().stream().anyMatch(input -> input.kind() == com.voltcanary.vcl.VclMetadata.Resource.BINDING));
    }

    @Test
    void latenessPreservesPreEpochMicroseconds() {
        assertEquals("1969-12-31T23:59:49.999999Z", watermark(inputs(Instant.EPOCH.minusNanos(1000))).literal().path("value").asText());
    }

    @Test
    void unknownOrIdleReferenceCannotBeDroppedToAdvanceFrontier() {
        var inputs = inputs(Instant.EPOCH);
        inputs.set(0, new VclTimingPlan.Progress(inputs.get(0).input(), null, null));
        assertEquals("TIMING_PROGRESS_UNKNOWN", watermark(inputs).reason());
        assertTrue(PLAN.idleExpired(Instant.EPOCH, Instant.EPOCH.plusSeconds(20)));
        assertEquals("TIMING_PROGRESS_UNKNOWN", watermark(inputs).reason());
        inputs.remove(0);
        assertEquals("TIMING_INPUTS_MISSING", watermark(inputs).reason());
    }

    @Test
    void overskewProgressIsQuarantinedInsteadOfMovingWatermark() {
        var inputs = inputs(Instant.EPOCH);
        inputs.set(0, new VclTimingPlan.Progress(inputs.get(0).input(), Instant.EPOCH.plusSeconds(16), Instant.EPOCH));
        assertEquals("FUTURE_PROGRESS_QUARANTINED", watermark(inputs).reason());
    }

    @ParameterizedTest
    @CsvSource({"14999999,false", "15000000,true", "15000001,true"})
    void idleDeadlineIsExactAndIndependentOfLateness(long elapsedUs, boolean expected) {
        assertEquals(expected, PLAN.idleExpired(Instant.EPOCH, Instant.EPOCH.plusNanos(elapsedUs * 1000)));
    }

    @Test
    void invalidClockAndProgressDoNotBecomeUnknownSuccess() {
        assertThrows(VclException.class, () -> PLAN.idleExpired(Instant.EPOCH, Instant.EPOCH.minusNanos(1000)));
        var inputs = inputs(Instant.EPOCH);
        inputs.set(0, new VclTimingPlan.Progress(inputs.get(0).input(), null, Instant.EPOCH));
        assertEquals("TIMING_PROGRESS_INVALID", watermark(inputs).reason());
        inputs.set(0, new VclTimingPlan.Progress(inputs.get(0).input(), Instant.EPOCH, null));
        assertEquals("WINDOW_TIME_INVALID", watermark(inputs).reason());
        assertEquals("TIMING_PROGRESS_REQUIRED", watermark(null).reason());
        assertEquals("TIMING_PROGRESS_REQUIRED", watermark(java.util.Arrays.asList((VclTimingPlan.Progress) null)).reason());
    }

    @Test
    void changedSchemaProgressCannotSubstituteForPinnedDependencies() {
        var inputs = inputs(Instant.EPOCH);
        var original = inputs.get(0).input();
        inputs.set(0, new VclTimingPlan.Progress(new VclCompilation.Dependency(original.kind(), original.id(), original.revision() + 1,
                original.snapshotDigest()), Instant.EPOCH, Instant.EPOCH));
        assertEquals("TIMING_INPUT_CHANGED", watermark(inputs).reason());
        assertEquals("TIMING_INPUT_DUPLICATE", watermark(Collections.nCopies(2, inputs(Instant.EPOCH).getFirst())).reason());
        assertEquals("TIMING_INPUT_LIMIT", watermark(Collections.nCopies(129, inputs(Instant.EPOCH).getFirst())).reason());
        assertThrows(UnsupportedOperationException.class, () -> PLAN.requiredInputs().clear());
    }

    @Test
    void frontierBeforeSupportedTimeRangeRemainsUnknown() {
        assertEquals("TIMING_BEFORE_RANGE", watermark(inputs(Instant.parse("0001-01-01T00:00:00Z"))).reason());
    }

    @Test
    void authorizationAndReplayPlanAreCheckedBeforeProgress() {
        assertEquals("ACCESS_SCOPE_CHANGED", PLAN.watermark(List.of(), PLAN.digest(), "b".repeat(64)).reason());
        assertEquals("TIMING_PLAN_CHANGED", PLAN.watermark(List.of(), "b".repeat(64), SCOPE).reason());
        var otherName = VclTimingPlan.compile(BundleFixtures.compilation("another label"));
        assertEquals(PLAN.digest(), otherName.digest());
        assertFalse(PLAN.toString().contains("allowedLateness"));
    }

    @Test
    void malformedInconsistentNodeLatenessAndChangedDocumentAreRejected() {
        var nodes = new ArrayList<>(COMPILED.graph());
        var absent = nodes.stream().filter(node -> node.method().equals("absent_within")).findFirst().orElseThrow();
        var config = (ObjectNode) absent.config();
        config.put("allowedLatenessUs", "20000000");
        nodes.set(nodes.indexOf(absent), new VclCompilation.Node(absent.id(), absent.method(), absent.inputs(), config, absent.outputKind(), absent.fields(), absent.sessionId()));
        assertThrows(VclException.class, () -> VclTimingPlan.compile(copy(COMPILED.documentDigest(), nodes)));
        assertThrows(VclException.class, () -> VclTimingPlan.compile(copy("f".repeat(64), COMPILED.graph())));
    }

    @Test
    void changedRootPolicyChangesReplayDigestAndSupportsZeroLateness() {
        var wire = (ObjectNode) BundleFixtures.document("timing").json();
        ((ObjectNode) wire.at("/spec/timing")).put("allowedLatenessUs", "0");
        for (var node : wire.at("/spec/nodes")) if (node.path("method").asText().equals("absent_within")) {
            ((ObjectNode) node.path("config")).put("allowedLatenessUs", "0");
        }
        var changed = BundleFixtures.COMPILER.compile(BundleFixtures.PARSER.parse(wire.toString()), BundleFixtures.METADATA);
        var zero = VclTimingPlan.compile(changed);
        assertNotEquals(PLAN.digest(), zero.digest());
        var inputs = zero.requiredInputs().stream().map(input -> new VclTimingPlan.Progress(input, Instant.EPOCH, Instant.EPOCH)).toList();
        assertEquals("1970-01-01T00:00:00Z", zero.watermark(inputs, zero.digest(), SCOPE).literal().path("value").asText());
    }

    @Test
    void maximumDeclaredDurationsCannotWrapIntoValidFrontiersOrExpiry() {
        var wire = (ObjectNode) BundleFixtures.document("timing-limits").json();
        var timing = (ObjectNode) wire.at("/spec/timing");
        timing.put("allowedLatenessUs", Long.toString(Long.MAX_VALUE)).put("maxFutureSkewUs", Long.toString(Long.MAX_VALUE))
                .put("idleTimeoutUs", Long.toString(Long.MAX_VALUE));
        for (var node : wire.at("/spec/nodes")) if (node.path("method").asText().equals("absent_within")) {
            ((ObjectNode) node.path("config")).put("allowedLatenessUs", Long.toString(Long.MAX_VALUE));
        }
        var compiled = BundleFixtures.COMPILER.compile(BundleFixtures.PARSER.parse(wire.toString()), BundleFixtures.METADATA);
        var plan = VclTimingPlan.compile(compiled);
        var min = Instant.parse("0001-01-01T00:00:00Z");
        var max = Instant.parse("9999-12-31T23:59:59.999999Z");
        assertEquals(VclTimingPlan.Admission.ELIGIBLE_TIME, plan.classify(max, min));
        assertFalse(plan.idleExpired(min, max));
        var inputs = plan.requiredInputs().stream().map(input -> new VclTimingPlan.Progress(input, min, min)).toList();
        assertEquals("TIMING_BEFORE_RANGE", plan.watermark(inputs, plan.digest(), SCOPE).reason());
    }

    @Test
    void timingPlanRejectsConflictingSnapshotsForOneInputAndMalformedPins() {
        var pins = new ArrayList<>(COMPILED.dependencies());
        var first = pins.getFirst();
        pins.add(new VclCompilation.Dependency(first.kind(), first.id(), first.revision() + 1, first.snapshotDigest()));
        assertEquals("TIMING_INPUT_DUPLICATE", assertThrows(VclException.class,
                () -> VclTimingPlan.compile(copy(COMPILED.documentDigest(), COMPILED.graph(), pins))).code());
        pins.removeLast();
        pins.set(0, new VclCompilation.Dependency(first.kind(), first.id(), first.revision(), "invalid"));
        assertEquals("TIMING_INPUT_INVALID", assertThrows(VclException.class,
                () -> VclTimingPlan.compile(copy(COMPILED.documentDigest(), COMPILED.graph(), pins))).code());
    }

    private static VclCompilation copy(String documentDigest, List<VclCompilation.Node> nodes) {
        return copy(documentDigest, nodes, COMPILED.dependencies());
    }

    private static VclCompilation copy(String documentDigest, List<VclCompilation.Node> nodes, List<VclCompilation.Dependency> pins) {
        return new VclCompilation(COMPILED.kind(), COMPILED.canonicalDocument(), documentDigest, COMPILED.semanticDigest(),
                COMPILED.registryVersion(), COMPILED.compilerVersion(), COMPILED.registryDigest(), COMPILED.unitRegistryDigest(),
                COMPILED.accessScopeDigest(), pins, nodes);
    }
    private static ArrayList<VclTimingPlan.Progress> inputs(Instant time) {
        var result = new ArrayList<VclTimingPlan.Progress>();
        for (var input : PLAN.requiredInputs()) result.add(new VclTimingPlan.Progress(input, time, time));
        return result;
    }
    private static ScalarValue watermark(List<VclTimingPlan.Progress> inputs) { return PLAN.watermark(inputs, PLAN.digest(), SCOPE); }
}
