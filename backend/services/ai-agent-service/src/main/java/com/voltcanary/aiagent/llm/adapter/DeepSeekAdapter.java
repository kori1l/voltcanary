package com.voltcanary.aiagent.llm.adapter;

import com.voltcanary.aiagent.domain.AiModelProviderType;
import com.voltcanary.aiagent.dto.TestModelProviderResponse;
import com.voltcanary.aiagent.llm.LlmPlanDraft;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DeepSeekAdapter implements AiModelAdapter {

    private final OpenAiCompatibleAdapter delegate;

    @Override
    public AiModelProviderType providerType() {
        return AiModelProviderType.DEEPSEEK;
    }

    @Override
    public TestModelProviderResponse test(AiResolvedModel model) {
        return delegate.test(model);
    }

    @Override
    public LlmPlanDraft plan(AiModelPlanRequest request) throws Exception {
        return delegate.plan(request);
    }
}
