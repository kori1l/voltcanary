package com.voltcanary.aiagent.llm;

import java.util.Map;

public record LlmActionDraft(
        String tool,
        String title,
        String description,
        Map<String, Object> parameters) {
}
