package com.voltcanary.aiagent.adapter.springai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.aiagent.llm.adapter.AiResolvedModel;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.Usage;
import com.voltcanary.aiagent.orchestration.routing.DispatchPermit;
import java.util.Set;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.client.AdvisorParams;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;

/** Exactly one model call; continuation, correction and tools return through the durable application loop. */
public final class SpringAiExchange {
    public record Result(String text, Usage usage, String failure) { }
    private final ObjectMapper mapper;
    private final ObservationRegistry observations;

    public SpringAiExchange(ObjectMapper mapper) {
        this(mapper, ObservationRegistry.NOOP);
    }

    public SpringAiExchange(ObjectMapper mapper, ObservationRegistry observations) {
        this.mapper = mapper;
        this.observations = observations;
    }

    public Result call(AiResolvedModel selected, String instructions, String input, JsonNode schema,
            boolean nativeTools, long timeout) throws Exception {
        return call(selected, instructions, input, schema, nativeTools, ReviewedProposalTool.NAME, timeout);
    }

    public Result call(AiResolvedModel selected, String instructions, String input, JsonNode schema,
            boolean nativeTools, String toolName, long timeout) throws Exception {
        return call(selected, instructions, input, schema, nativeTools, toolName, timeout, null);
    }

    public Result call(AiResolvedModel selected, String instructions, String input, JsonNode schema,
            boolean nativeTools, String toolName, long timeout, DispatchPermit permit) throws Exception {
        // Validate deployment-owned names before opening a provider connection.
        DashboardProposalTools.description(toolName);
        try (var lease = new SpringAiModels(observations).open(selected, timeout, schema == null ? null : schema.toString(), nativeTools)) {
            // Input includes the persisted bounded dialogue and exact pending question. It remains untrusted user data.
            var messages = SpringAiConversationContext.messages(mapper, instructions, input);
            var request = ChatClient.create(lease.model(), observations).prompt()
                    .messages(messages)
                    .advisors(AdvisorParams.toolCallingAdvisorAutoRegister(false));
            var tool = nativeTools ? new ReviewedProposalTool(toolName, schema.toString()) : null;
            if (tool != null) request.tools(tool);
            if (permit != null) {
                request.advisors(new PreparedManifestAdvisor(messages, lease.model().getOptions(), tool),
                        new ReservedDispatchAdvisor(permit));
            }
            var result = decode(request.call().chatResponse(), nativeTools, toolName);
            var diagnostics = new java.util.LinkedHashMap<String, Object>();
            diagnostics.put("providerStatus", result.failure() == null ? "completed" : "rejected");
            diagnostics.put("finalTextCharacters", result.text() == null ? 0 : result.text().length());
            if (result.usage() != null) {
                diagnostics.put("inputTokens", result.usage().inputTokens());
                diagnostics.put("outputTokens", result.usage().outputTokens());
                diagnostics.put("cachedInputTokens", result.usage().cachedInputTokens());
                diagnostics.put("internalTokenCount", result.usage().reasoningTokens());
            }
            com.voltcanary.aiagent.trace.AssistantTrace.event("PROVIDER_USAGE", "OBSERVED", result.failure(), diagnostics);
            return result;
        }
    }

    public Result decode(ChatResponse response, boolean nativeTools) {
        return decode(response, nativeTools, ReviewedProposalTool.NAME);
    }

    public Result decode(ChatResponse response, boolean nativeTools, String toolName) {
        if (response == null || response.getResults().size() != 1) return new Result("", null, "PROVIDER_OUTPUT_INVALID");
        var usage = usage(response);
        var generation = response.getResult();
        String finish = generation.getMetadata().getFinishReason();
        if (finish != null) finish = finish.toLowerCase(java.util.Locale.ROOT);
        if (finish != null && Set.of("length", "max_tokens", "incomplete").contains(finish))
            return new Result("", usage, "PROVIDER_RESPONSE_INCOMPLETE");
        if (finish != null && Set.of("content_filter", "refusal", "safety", "recitation").contains(finish))
            return new Result("", usage, "PROVIDER_REFUSAL");
        var message = generation.getOutput();
        if (message.getMetadata().get("refusal") instanceof String refusal && !refusal.isBlank())
            return new Result("", usage, "PROVIDER_REFUSAL");
        if (nativeTools) {
            var calls = message.getToolCalls();
            if (calls.size() != 1 || !toolName.equals(calls.getFirst().name()))
                return new Result("", usage, "PROVIDER_TOOL_INVALID");
            return new Result(calls.getFirst().arguments(), usage, null);
        }
        if (message.hasToolCalls()) return new Result("", usage, "PROVIDER_TOOL_INVALID");
        if (message.getText() == null || message.getText().isBlank()) return new Result("", usage, "PROVIDER_OUTPUT_INVALID");
        return new Result(message.getText(), usage, null);
    }

    private Usage usage(ChatResponse response) {
        var value = response.getMetadata().getUsage();
        if (value == null || value.getPromptTokens() == null || value.getCompletionTokens() == null) return null;
        if (value.getNativeUsage() == null && value.getTotalTokens() == 0) return null;
        long cached = value.getCacheReadInputTokens() == null ? 0 : value.getCacheReadInputTokens();
        long reasoning = 0;
        // Read typed accounting fields only; never serialize an SDK response graph.
        if (value.getNativeUsage() instanceof com.openai.models.completions.CompletionUsage openai) {
            reasoning = openai.completionTokensDetails().flatMap(details -> details.reasoningTokens()).orElse(0L);
            cached = Math.max(cached, openai.promptTokensDetails().flatMap(details -> details.cachedTokens()).orElse(0L));
        } else if (value instanceof org.springframework.ai.google.genai.metadata.GoogleGenAiUsage google) {
            reasoning = google.getThoughtsTokenCount() == null ? 0 : google.getThoughtsTokenCount();
        }
        return new Usage(value.getPromptTokens().longValue(), cached, value.getCompletionTokens().longValue(), reasoning, null);
    }
}
