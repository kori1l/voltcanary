package com.voltcanary.aiagent.llm.adapter;

import com.voltcanary.aiagent.provider.AiModelProviderType;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class AiModelAdapterRegistry {
    private final Map<AiModelProviderType, AiModelAdapter> adapters = new EnumMap<>(AiModelProviderType.class);

    public AiModelAdapterRegistry(List<AiModelAdapter> adapters) {
        for (AiModelAdapter adapter : adapters) {
            this.adapters.put(adapter.providerType(), adapter);
        }
    }

    public AiModelAdapter adapterFor(AiModelProviderType type) {
        AiModelAdapter adapter = adapters.get(type);
        if (adapter == null && type == AiModelProviderType.DEEPSEEK) {
            adapter = adapters.get(AiModelProviderType.OPENAI_COMPATIBLE);
        }
        if (adapter == null) {
            throw new IllegalArgumentException("No adapter for provider " + type);
        }
        return adapter;
    }
}
