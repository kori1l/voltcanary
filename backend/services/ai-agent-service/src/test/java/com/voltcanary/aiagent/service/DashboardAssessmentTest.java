package com.voltcanary.aiagent.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.aiagent.orchestration.contract.AssessmentSources;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.AssessmentCheck;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.AssessmentResponse;
import com.voltcanary.aiagent.orchestration.plan.IntentPlans;
import java.util.List;
import org.junit.jupiter.api.Test;

class DashboardAssessmentTest {

    @Test
    void satisfiedVerdictNeedsNonblankQuotesFromCurrentAndPendingSources() throws Exception {
        var mapper = new ObjectMapper();
        var request = mapper.readTree(
            """
            {"messageId":"current","message":"Move the speed card","context":{
              "goals":[{"disposition":"PENDING","sourceMessageId":"prior"},{"disposition":"SUPERSEDED","sourceMessageId":"obsolete"}],
              "dialogue":{"messages":[{"role":"USER","messageId":"prior","text":"Keep the map"},
                                     {"role":"USER","messageId":"current","text":"Stale copy"}]}}}
            """
        );
        var response = mapper.createObjectNode().put("verdict", "SATISFIED");
        var checks = response.putArray("checks");
        checks.addObject().put("sourceMessageId", "current").put("sourceQuote", "speed card");
        assertEquals(
            "ASSESSMENT_SOURCE_COVERAGE_INCOMPLETE",
            AssessmentSources.violation(request, response)
        );
        var prior = checks.addObject().put("sourceMessageId", "prior").put("sourceQuote", " ");
        assertEquals(
            "ASSESSMENT_SOURCE_EVIDENCE_INVALID",
            AssessmentSources.violation(request, response)
        );
        prior.put("sourceQuote", "Keep the map");
        assertNull(AssessmentSources.violation(request, response));
    }

    @Test
    void uncertaintyCannotTriggerRepairForAnUnprovenMismatch() {
        var definition = new ObjectMapper().createObjectNode();
        definition.putArray("widgets");
        var uncertain = new AssessmentCheck(
            "source",
            "title",
            "Title",
            "UNCERTAIN",
            List.of(),
            "Insufficient evidence"
        );
        var rejected = new AssessmentCheck(
            "source",
            "title",
            "Title",
            "REJECTED",
            List.of(),
            "Title missing"
        );
        assertThrows(IntentPlans.Rejected.class, () ->
            DashboardAssessment.validateEvidence(
                report("REJECTED", uncertain),
                "candidate",
                definition
            )
        );
        assertDoesNotThrow(() ->
            DashboardAssessment.validateEvidence(
                report("UNCERTAIN", uncertain),
                "candidate",
                definition
            )
        );
        assertDoesNotThrow(() ->
            DashboardAssessment.validateEvidence(
                report("REJECTED", uncertain, rejected),
                "candidate",
                definition
            )
        );
    }

    private AssessmentResponse report(String verdict, AssessmentCheck... checks) {
        return new AssessmentResponse(
            "assistant-v2",
            "assessment.response",
            "candidate",
            verdict,
            List.of(checks)
        );
    }
}
