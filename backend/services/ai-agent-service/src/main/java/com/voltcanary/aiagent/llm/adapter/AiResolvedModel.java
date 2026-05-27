package com.voltcanary.aiagent.llm.adapter;

import com.voltcanary.aiagent.provider.AiModelConfig;
import com.voltcanary.aiagent.provider.AiModelProvider;

public record AiResolvedModel(AiModelProvider provider, AiModelConfig modelConfig, String apiKey) {
}
