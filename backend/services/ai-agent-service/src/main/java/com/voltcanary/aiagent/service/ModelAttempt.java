package com.voltcanary.aiagent.service;

import com.voltcanary.aiagent.llm.LlmPlanDraft;
import com.voltcanary.aiagent.llm.adapter.AiModelAdapterRegistry;
import com.voltcanary.aiagent.llm.adapter.AiModelPlanRequest;
import com.voltcanary.aiagent.trace.AssistantTrace;
import java.util.Map;

/** Observable provider invocation; retry policy stays with the existing planning application. */
final class ModelAttempt {

    private ModelAttempt() {}

    static LlmPlanDraft plan(
        AiModelAdapterRegistry registry,
        AiModelPlanRequest request
    ) throws Exception {
        var model = request.resolvedModel();
        try (
            var attempt = AssistantTrace.start(
                "MODEL_CALL",
                Map.of(
                    "providerId",
                    model.provider().getId(),
                    "modelConfigId",
                    model.modelConfig().getId(),
                    "model",
                    model.modelConfig().getModelId(),
                    "contextFields",
                    request.context().keySet()
                )
            )
        ) {
            try {
                var result = registry.adapterFor(model.provider().getProviderType()).plan(request);
                attempt.success(
                    Map.of("actionCount", result.actions() == null ? 0 : result.actions().size())
                );
                return result;
            } catch (Exception failure) {
                attempt.failure(
                    "MODEL_CALL_FAILED",
                    Map.of("failureType", failure.getClass().getSimpleName())
                );
                throw failure;
            }
        }
    }
}
