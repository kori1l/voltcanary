package com.voltcanary.vcl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VclObservationPageTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final VclObservationReadiness.Pin PIN = new VclObservationReadiness.Pin(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "private-source", 1, UUID.randomUUID(), "a".repeat(64));
    private static final UUID GENERATION = UUID.randomUUID(), BOOLEAN = UUID.randomUUID(), TEXT = UUID.randomUUID();

    @Test void requestAndPageRoundTripExactFalseEmptyTextAndConservativeFlags() {
        var request = request("0", "2", 1);
        assertEquals(request, VclObservationRead.parse(request.bytes()));
        var page = VclObservationPage.of(request, List.of(row("1")), "PAGE_ROWS");
        var actual = VclObservationPage.parse(page.bytes());
        assertEquals(request, actual.request());
        assertEquals("1", actual.nextCheckpoint());
        assertTrue(actual.hasMore());
        assertFalse(actual.observations().getFirst().at("/fields/0/value").asBoolean());
        assertEquals("", actual.observations().getFirst().at("/fields/1/value").asText());
        assertEquals("2026-11-05T00:00:01.123456789Z", actual.observations().getFirst().path("receivedAt").asText());
        assertFalse(actual.negativeEvidenceReady());
        assertFalse(actual.ruleExecutionReady());
        assertFalse(actual.toString().contains("private-source"));
    }

    @Test void sameTimestampIndependentFactsAndByteTruncatedPrefixesArePreserved() {
        var request = request("0", "2", 128);
        var first = row("1");
        var second = row("2");
        ((ObjectNode) second.at("/fields/0")).put("value", true);
        var actual = VclObservationPage.parse(VclObservationPage.of(request, List.of(first, second), null).bytes());
        assertEquals(2, actual.observations().size());
        assertFalse(actual.hasMore());
        assertEquals(first.path("eventTime"), second.path("eventTime"));
        var truncated = VclObservationPage.parse(VclObservationPage.of(request, List.of(first), "PAGE_BYTES").bytes());
        assertTrue(truncated.hasMore());
        assertEquals("PAGE_BYTES", truncated.truncationReason());
    }

    @Test void emptyExactCutAndMaximumLongCheckpointDoNotInventRowsOrOverflow() {
        var empty = VclObservationPage.parse(VclObservationPage.of(request("5", "5", 10), List.of(), null).bytes());
        assertTrue(empty.observations().isEmpty());
        assertEquals("5", empty.nextCheckpoint());
        assertFalse(empty.hasMore());
        String maximum = Long.toString(Long.MAX_VALUE);
        var request = request(Long.toString(Long.MAX_VALUE - 1), maximum, 1);
        var actual = VclObservationPage.parse(VclObservationPage.of(request, List.of(row(maximum)), null).bytes());
        assertEquals(maximum, actual.nextCheckpoint());
    }

    @Test void unknownIsDistinctFromFalseAndCannotBeRelabelledAsKnownNull() {
        var value = row("1");
        ((ObjectNode) value.at("/fields/0")).put("knowledge", "UNKNOWN").put("reason", "MISSING_OR_NULL_INPUT").putNull("value");
        assertEquals("UNKNOWN", VclObservationPage.parse(VclObservationPage.of(request("0", "1", 1), List.of(value), null).bytes()).observations().getFirst().at("/fields/0/knowledge").asText());
        ((ObjectNode) value.at("/fields/0")).put("knowledge", "KNOWN").putNull("reason");
        invalidRow(value);
    }

    @Test void checkpointGapsDuplicatesAndImpossibleTruncationCannotBecomeACompletePage() {
        assertThrows(VclException.class, () -> VclObservationPage.of(request("0", "2", 10), List.of(row("2")), null));
        var duplicate = row("2");
        duplicate.put("inputEventId", row("1").path("inputEventId").asText());
        assertThrows(VclException.class, () -> VclObservationPage.of(request("0", "2", 10), List.of(row("1"), duplicate), null));
        assertThrows(VclException.class, () -> VclObservationPage.of(request("0", "2", 10), List.of(), "PAGE_BYTES"));
        assertThrows(VclException.class, () -> VclObservationPage.of(request("0", "2", 10), List.of(row("1")), "PAGE_ROWS"));
    }

    @Test void requestRejectsUnboundedOrNonExactCheckpointAndLimitShapes() throws Exception {
        var original = (ObjectNode) JSON.readTree(request("0", "2", 1).bytes());
        for (Consumer<ObjectNode> change : List.<Consumer<ObjectNode>>of(
                value -> value.put("limit", 0), value -> value.put("limit", 129), value -> value.put("limit", 1.5), value -> value.put("limit", "1"),
                value -> value.put("afterCheckpoint", 0), value -> value.put("afterCheckpoint", "00"), value -> value.put("afterCheckpoint", "3"),
                value -> value.put("throughCheckpoint", "9223372036854775808"), value -> value.put("executionReady", true),
                value -> value.put("inputGeneration", PIN.snapshotId().toString()))) {
            var changed = original.deepCopy();
            change.accept(changed);
            assertEquals("OBSERVATION_READ_INVALID", assertThrows(VclException.class, () -> VclObservationRead.parse(changed.toString().getBytes(StandardCharsets.UTF_8))).code());
        }
    }

    @Test void generationScopeReadinessAndTimestampForgeryAreRejected() {
        for (Consumer<ObjectNode> change : List.<Consumer<ObjectNode>>of(
                value -> value.put("inputGeneration", UUID.randomUUID().toString()), value -> value.put("ruleExecutionReady", true),
                value -> value.put("negativeEvidenceReady", true), value -> value.put("upstreamCoverage", "COMPLETE"),
                value -> value.put("eventTimeProvenance", "QUALIFIED"), value -> value.put("eventTime", "2026-11-05T00:00:00.000000001Z"),
                value -> value.put("recordedAt", "+10000-01-01T00:00:00Z"), value -> value.put("approved", true),
                value -> ((ObjectNode) value.path("bindingSchemaPin")).put("accountId", UUID.randomUUID().toString()))) {
            var value = row("1");
            change.accept(value);
            invalidRow(value);
        }
    }

    @Test void malformedKnownValuesUnknownReasonsAndDuplicateFieldIdsFailClosed() {
        for (Consumer<ObjectNode> change : List.<Consumer<ObjectNode>>of(
                value -> value.put("type", "INT64"), value -> value.put("value", "false"), value -> value.put("reason", "private explanation"),
                value -> value.put("knowledge", "UNKNOWN"), value -> value.put("fieldId", TEXT.toString()), value -> value.put("extra", true))) {
            var value = row("1");
            change.accept((ObjectNode) value.at("/fields/0"));
            invalidRow(value);
        }
        var value = row("1");
        ((ObjectNode) value.at("/fields/1")).put("value", "x".repeat(513));
        invalidRow(value);
    }

    @Test void rootFlagExpansionMalformedJsonAndOversizedWireAreRejected() throws Exception {
        var original = (ObjectNode) JSON.readTree(VclObservationPage.of(request("0", "1", 1), List.of(row("1")), null).bytes());
        for (Consumer<ObjectNode> change : List.<Consumer<ObjectNode>>of(
                value -> value.put("hasMore", "false"), value -> value.put("hasMore", true), value -> value.put("ruleExecutionReady", true),
                value -> value.put("upstreamCoverage", "COMPLETE"), value -> value.put("extra", true), value -> value.put("nextCheckpoint", 1))) {
            var changed = original.deepCopy();
            change.accept(changed);
            assertThrows(VclException.class, () -> VclObservationPage.parse(changed.toString().getBytes(StandardCharsets.UTF_8)));
        }
        for (byte[] wire : List.of((original + "{}").getBytes(StandardCharsets.UTF_8), new byte[VclObservationPage.MAX_BYTES + 1],
                original.toString().replace("\"hasMore\":false", "\"hasMore\":false,\"hasMore\":false").getBytes(StandardCharsets.UTF_8))) {
            assertThrows(VclException.class, () -> VclObservationPage.parse(wire));
        }
    }

    @Test void snapshotsDefensivelyCopyRawObservationValues() {
        var value = row("1");
        var page = VclObservationPage.of(request("0", "1", 1), List.of(value), null);
        value.put("ruleExecutionReady", true);
        ((ObjectNode) page.observations().getFirst()).put("ruleExecutionReady", true);
        assertFalse(page.observations().getFirst().path("ruleExecutionReady").booleanValue());
    }

    @Test void rowTypesMustAlsoMatchTheSeparatelyAuthenticatedSchema() {
        var definition = JSON.createObjectNode();
        var fields = definition.putArray("fields");
        fields.addObject().put("id", BOOLEAN.toString()).put("type", "BOOLEAN");
        fields.addObject().put("id", TEXT.toString()).put("type", "TEXT");
        var page = VclObservationPage.of(request("0", "1", 1), List.of(row("1")), null);
        page.requireSchema(definition);
        ((ObjectNode) fields.get(0)).put("id", UUID.randomUUID().toString());
        assertThrows(VclException.class, () -> page.requireSchema(definition));
    }

    private static void invalidRow(ObjectNode row) {
        assertThrows(VclException.class, () -> VclObservationPage.of(request("0", "1", 1), List.of(row), null));
    }
    private static VclObservationRead request(String after, String through, int limit) { return new VclObservationRead(PIN, GENERATION, after, through, limit); }
    private static ObjectNode row(String checkpoint) {
        var value = JSON.createObjectNode().put("profile", VclObservationProfile.VERSION).put("inputEventId", new UUID(0, Long.parseLong(checkpoint)).toString())
                .put("inputGeneration", GENERATION.toString()).put("eventTime", "2026-11-05T00:00:00Z").put("receivedAt", "2026-11-05T00:00:01.123456789Z")
                .put("recordedAt", "2026-11-05T00:00:02Z").put("checkpoint", checkpoint).put("upstreamCoverage", "UNKNOWN")
                .put("eventTimeProvenance", "UNQUALIFIED").put("negativeEvidenceReady", false).put("ruleExecutionReady", false);
        value.set("bindingSchemaPin", JSON.valueToTree(PIN));
        var fields = value.putArray("fields");
        fields.addObject().put("fieldId", BOOLEAN.toString()).put("type", "BOOLEAN").put("knowledge", "KNOWN").putNull("reason").put("value", false);
        fields.addObject().put("fieldId", TEXT.toString()).put("type", "TEXT").put("knowledge", "KNOWN").putNull("reason").put("value", "");
        return value;
    }
}
