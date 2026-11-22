package com.voltcanary.aiagent.llm;

import java.util.List;

public record LlmPlanDraft(
    String provider,
    String model,
    String summary,
    String explanation,
    List<LlmActionDraft> actions,
    com.voltcanary.aiagent.model.PlanningQuestion clarification
) {
    public LlmPlanDraft(
        String provider,
        String model,
        String summary,
        String explanation,
        List<LlmActionDraft> actions
    ) {
        this(provider, model, summary, explanation, actions, null);
    }
}
