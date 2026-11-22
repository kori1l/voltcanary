package com.voltcanary.aiagent.adapter.springai;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/** A typed proposal contract, never a mutation capability. The durable executor owns confirmation. */
public final class ReviewedProposalTool implements ToolCallback {

    public static final String NAME = "assistant_proposal";
    private final ToolDefinition definition;

    public ReviewedProposalTool(String schema) {
        this(NAME, schema);
    }

    public ReviewedProposalTool(String name, String schema) {
        definition = ToolDefinition.builder()
            .name(name)
            .description(DashboardProposalTools.description(name))
            .inputSchema(schema)
            .build();
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return definition;
    }

    @Override
    public String call(String input) {
        throw new IllegalStateException("PROPOSAL_REQUIRES_DURABLE_REVIEW");
    }
}
