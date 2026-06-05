package com.voltcanary.aiagent.llm.adapter;

import com.voltcanary.aiagent.domain.AiModelProviderType;
import com.voltcanary.aiagent.dto.TestModelProviderResponse;
import com.voltcanary.aiagent.llm.LlmPlanDraft;


public interface AiModelAdapter {
    AiModelProviderType providerType();
    TestModelProviderResponse test(AiResolvedModel model);
    LlmPlanDraft plan(AiModelPlanRequest request) throws Exception;
}
