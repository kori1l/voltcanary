package com.voltcanary.aiagent.llm.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.aiagent.domain.AiModelProviderType;
import org.springframework.stereotype.Component;

@Component
public final class OpenAiCompatibleAdapter extends SpringAiPlanAdapter {

    public OpenAiCompatibleAdapter(ObjectMapper mapper, JsonPlanParser parser) {
        super(mapper, parser);
    }

    @Override
    public AiModelProviderType providerType() {
        return AiModelProviderType.OPENAI_COMPATIBLE;
    }
}
