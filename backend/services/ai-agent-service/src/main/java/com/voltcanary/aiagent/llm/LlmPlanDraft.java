package com.voltcanary.aiagent.llm;

import java.util.List;

public record LlmPlanDraft(
        String provider,
        String model,
        String summary,
        String explanation,
        List<LlmActionDraft> actions) {
}
