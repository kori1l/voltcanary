package com.voltcanary.aiagent.orchestration.routing;

import com.fasterxml.jackson.databind.JsonNode;

/** Public model proposal only; never provider envelopes or private reasoning. Kept in memory for one correction. */
public final class ModelProposalRejected extends IllegalArgumentException {
    private final JsonNode proposal;
    private final String diagnostic;

    public ModelProposalRejected(JsonNode proposal, String diagnostic) {
        super("MODEL_PROPOSAL_CONTRACT_INVALID");
        this.proposal = proposal.deepCopy();
        this.diagnostic = diagnostic;
    }

    public JsonNode proposal() { return proposal.deepCopy(); }
    public String diagnostic() { return diagnostic; }
}
