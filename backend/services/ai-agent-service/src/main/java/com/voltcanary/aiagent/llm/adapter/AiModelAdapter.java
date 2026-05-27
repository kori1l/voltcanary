package com.voltcanary.aiagent.llm.adapter;

import com.voltcanary.aiagent.dto.TestModelProviderResponse;
import com.voltcanary.aiagent.llm.LlmPlanDraft;
import com.voltcanary.aiagent.provider.AiModelProviderType;

public interface AiModelAdapter {
    AiModelProviderType providerType();
    TestModelProviderResponse test(AiResolvedModel model);
    LlmPlanDraft plan(AiModelPlanRequest request) throws Exception;
}
