package com.voltcanary.aiagent.llm;

import java.util.Map;
import java.util.Optional;

public interface LlmPlanProvider {
    Optional<LlmPlanDraft> plan(String message, Map<String, Object> context);
}
