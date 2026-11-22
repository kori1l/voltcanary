package com.voltcanary.aiagent;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.aiagent.adapter.DashboardProposalIdentifiers;
import org.junit.jupiter.api.Test;

class DashboardProposalIdentifiersTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void technicalCollisionsDoNotChangeTargetsOrOperations() throws Exception {
        var original = mapper.readTree(
            """
            {"outcome":"PREPARED","command":{"payload":{"operations":[
             {"op":"place_widgets","id":"move","targets":["existing-a"],"position":"TOP"},
             {"op":"place_widgets","id":"move","targets":["existing-b"],"position":"BOTTOM"}]}}}
            """
        );
        var normalized = DashboardProposalIdentifiers.normalize(original);
        assertNotEquals(
            normalized.at("/command/payload/operations/0/id"),
            normalized.at("/command/payload/operations/1/id")
        );
        assertEquals(
            original.at("/command/payload/operations/1/targets"),
            normalized.at("/command/payload/operations/1/targets")
        );
        assertEquals("BOTTOM", normalized.at("/command/payload/operations/1/position").asText());
        assertEquals("move", original.at("/command/payload/operations/1/id").asText());
        assertEquals(normalized, DashboardProposalIdentifiers.normalize(normalized));
    }

    @Test
    void ambiguousReferencesRemainRejectedRatherThanGuessed() throws Exception {
        var proposal = mapper.readTree(
            """
            {"outcome":"PREPARED","command":{"payload":{"operations":[
             {"op":"show_map","id":"map"},{"op":"show_map","id":"map"},
             {"op":"place_widgets","id":"move","targets":["@map"]}]}}}
            """
        );
        assertEquals(proposal, DashboardProposalIdentifiers.normalize(proposal));
    }
}
