package com.voltcanary.alert.configurable.adapter;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WindowAlertProposalTest {

    @Test
    void proofPinIsSeparateFromTheFullCoverageDigestAndLegacyRemainsReadable() {
        var value = proposal();
        assertEquals("f".repeat(64), parse(value).expectedProofDigest());
        value.put("profile", "VCL_WINDOW_ALERT_PROPOSAL/1").remove("coverageProofDigest");
        assertNull(parse(value).expectedProofDigest());
    }

    @Test
    void rejectsMissingOrMalformedProofPinAndUnknownFields() {
        var missing = proposal();
        missing.remove("coverageProofDigest");
        invalid(missing);
        var malformed = proposal().put("coverageProofDigest", "not-a-proof");
        invalid(malformed);
        invalid(proposal().put("unexpected", true));
    }

    @Test
    void rejectsChangedOccurrenceDedupAndNoncanonicalBytes() {
        invalid(proposal().put("occurrenceDigest", "e".repeat(64)));
        invalid(proposal().put("dedup", "e".repeat(64)));
        byte[] noncanonical = (VclCanonical.encode(proposal()) + "\n").getBytes(
            StandardCharsets.UTF_8
        );
        assertEquals(
            "ALERT_PROPOSAL_INVALID",
            assertThrows(VclException.class, () -> WindowAlertProposal.parse(noncanonical)).code()
        );
    }

    @Test
    void prefixesMustEndStrictlyInsideTheOriginalWindow() {
        var value = proposal().put("profile", "VCL_COUNT_PREFIX_ALERT_PROPOSAL/1");
        value.remove("coverageProofDigest");
        value.put("measuredThrough", "2026-11-01T00:00:05Z");
        assertTrue(parse(value).prefix());
        value.put("measuredThrough", value.path("windowEnd").textValue());
        invalid(value);
    }

    static ObjectNode proposal() {
        var value = JsonNodeFactory.instance
            .objectNode()
            .put("profile", "VCL_WINDOW_ALERT_PROPOSAL/2");
        for (String field : new String[] {
            "account",
            "rule",
            "epoch",
            "grant",
            "policy",
            "snapshot",
            "generation",
            "measure",
            "coverageProof",
            "binding",
        })
            value.put(field, UUID.randomUUID().toString());
        for (String field : new String[] {
            "bundleDigest",
            "grantDigest",
            "policyDigest",
            "measureDigest",
            "coverageDigest",
            "subjectDigest",
        })
            value.put(field, "a".repeat(64));
        value
            .put("grantEpoch", "1")
            .put("schemaRevision", "1")
            .put("severity", "WARNING")
            .put("windowStart", "2026-11-01T00:00:00Z")
            .put("windowEnd", "2026-11-01T00:00:10Z");
        String occurrence = VclCanonical.sha256(
            "RULE_WINDOW_OCCURRENCE/1\n" +
                value.path("windowStart").textValue() +
                "\n" +
                value.path("windowEnd").textValue()
        );
        value
            .put("occurrenceDigest", occurrence)
            .put(
                "dedup",
                VclCanonical.sha256(
                    "RULE_SUBJECT_OCCURRENCE/1\n" +
                        value.path("rule").textValue() +
                        "\n" +
                        value.path("subjectDigest").textValue() +
                        "\n" +
                        occurrence
                )
            );
        return value.put("coverageProofDigest", "f".repeat(64));
    }

    private static WindowAlertProposal parse(ObjectNode value) {
        return WindowAlertProposal.parse(
            VclCanonical.encode(value).getBytes(StandardCharsets.UTF_8)
        );
    }

    private static void invalid(ObjectNode value) {
        assertEquals(
            "ALERT_PROPOSAL_INVALID",
            assertThrows(VclException.class, () -> parse(value)).code()
        );
    }
}
