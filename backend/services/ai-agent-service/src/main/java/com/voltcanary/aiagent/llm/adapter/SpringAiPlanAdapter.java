package com.voltcanary.aiagent.llm.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.aiagent.adapter.springai.SpringAiExchange;
import com.voltcanary.aiagent.dto.TestModelProviderResponse;
import com.voltcanary.aiagent.llm.LlmPlanDraft;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Compatibility facade for existing plan endpoints, backed by the same Spring AI engine. */
public abstract class SpringAiPlanAdapter implements AiModelAdapter {
    private final ObjectMapper mapper;
    private final JsonPlanParser parser;

    protected SpringAiPlanAdapter(ObjectMapper mapper, JsonPlanParser parser) {
        this.mapper = mapper;
        this.parser = parser;
    }

    @Override
    public TestModelProviderResponse test(AiResolvedModel model) {
        return new TestModelProviderResponse("READY", "Spring AI provider is configured.",
                model.provider().getProviderType().name(), model.modelConfig().getModelId(), 0, Instant.now());
    }

    @Override
    public LlmPlanDraft plan(AiModelPlanRequest request) throws Exception {
        var selected = request.resolvedModel();
        Map<String, Object> context = request.context() == null ? Map.of() : request.context();
        var input = new LinkedHashMap<String, Object>();
        input.put("message", request.message());
        input.put("context", context);
        JsonNode schema = null;
        if (selected.modelConfig().isSupportsJson()) {
            if (com.voltcanary.aiagent.service.DashboardOperationProtocol.VERSION.equals(context.get("dashboardPlanningProtocol")))
                schema = mapper.valueToTree(com.voltcanary.aiagent.service.DashboardOperationSchema.response(context));
            else if (context.containsKey("originalDeviceRequest") && !context.containsKey("dashboardWidgetTypes"))
                schema = mapper.valueToTree(com.voltcanary.aiagent.service.DeviceCreationSchema.response(context));
        }
        long timeout = selected.modelConfig().getTimeoutMs() == null ? 30_000 : selected.modelConfig().getTimeoutMs();
        var result = new SpringAiExchange(mapper).call(selected, request.systemPrompt(), mapper.writeValueAsString(input), schema, false, timeout);
        if ("PROVIDER_RESPONSE_INCOMPLETE".equals(result.failure())) throw new IncompleteModelResponseException();
        if (result.failure() != null) throw new IllegalArgumentException(result.failure());
        return parser.parse(selected.provider().getProviderType().name(), selected.modelConfig().getModelId(), result.text());
    }
}
