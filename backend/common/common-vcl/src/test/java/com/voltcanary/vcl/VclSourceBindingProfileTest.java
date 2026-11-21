package com.voltcanary.vcl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VclSourceBindingProfileTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void directOccurrenceUsesOnlyRetainedTypedIdentityFields() throws Exception {
        var definition = definition();
        var fields = VclSourceBindingProfile.occurrenceFields(definition).orElseThrow();
        assertEquals(new UUID(0, 1), fields.subject());
        assertEquals(new UUID(0, 2), fields.occurrence());
        ((ObjectNode) definition.withArray("fieldMappings").get(1)).put("vclReady", false);
        assertThrows(IllegalArgumentException.class, () -> VclSourceBindingProfile.occurrenceFields(definition));
    }

    @Test void rejectsAliasedKeysUnsupportedVersionsAndExtraRoutingOptions() throws Exception {
        for (String contract : new String[] {
                "{\"version\":\"direct-occurrence-1\",\"subjectKey\":\"asset\",\"occurrenceKey\":\"asset\"}",
                "{\"version\":\"direct-occurrence-2\",\"subjectKey\":\"asset\",\"occurrenceKey\":\"trip\"}",
                "{\"version\":\"direct-occurrence-1\",\"subjectKey\":\"asset\",\"occurrenceKey\":\"trip\",\"fallback\":true}",
                "{\"version\":\"direct-occurrence-1\",\"subjectKey\":\"asset\",\"occurrenceKey\":\"trip\",\"fanOut\":2}",
                "{\"version\":\"expectation-deadline-1\",\"subjectKey\":\"asset\",\"occurrenceKey\":\"trip\"}" }) {
            var definition = definition();
            definition.set("occurrenceRouting", JSON.readTree(contract));
            assertThrows(IllegalArgumentException.class, () -> VclSourceBindingProfile.occurrenceFields(definition));
        }
    }

    @Test void identityCannotUseAnUnretainedOrNonTextField() throws Exception {
        var wrongType = definition();
        ((ObjectNode) wrongType.withArray("fields").get(1)).put("type", "UUID");
        assertThrows(IllegalArgumentException.class, () -> VclSourceBindingProfile.occurrenceFields(wrongType));
        var missing = definition();
        missing.withArray("fields").remove(1);
        assertThrows(IllegalArgumentException.class, () -> VclSourceBindingProfile.occurrenceFields(missing));
        assertTrue(VclSourceBindingProfile.occurrenceFields(JSON.createObjectNode()).isEmpty());
    }

    private ObjectNode definition() throws Exception {
        return (ObjectNode) JSON.readTree("""
                {"occurrenceRouting":{"version":"direct-occurrence-1","subjectKey":"asset","occurrenceKey":"trip"},
                 "fieldMappings":[
                   {"sourceKey":"asset","sourceType":"string","vclReady":true,"fieldId":"00000000-0000-0000-0000-000000000001"},
                   {"sourceKey":"trip","sourceType":"string","vclReady":true,"fieldId":"00000000-0000-0000-0000-000000000002"}],
                 "fields":[{"id":"00000000-0000-0000-0000-000000000001","type":"TEXT"},
                           {"id":"00000000-0000-0000-0000-000000000002","type":"TEXT"}]}
                """);
    }
}
