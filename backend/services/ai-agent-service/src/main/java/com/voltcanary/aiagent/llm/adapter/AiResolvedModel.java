package com.voltcanary.aiagent.llm.adapter;

import com.voltcanary.aiagent.domain.AiModelConfig;
import com.voltcanary.aiagent.domain.AiModelProvider;


public record AiResolvedModel(AiModelProvider provider, AiModelConfig modelConfig, String apiKey) {
}
