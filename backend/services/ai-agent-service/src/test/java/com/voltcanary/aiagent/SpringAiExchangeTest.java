package com.voltcanary.aiagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.voltcanary.aiagent.adapter.springai.SpringAiExchange;
import com.voltcanary.aiagent.adapter.springai.SpringAiFailures;
import com.voltcanary.aiagent.domain.AiModelConfig;
import com.voltcanary.aiagent.domain.AiModelProvider;
import com.voltcanary.aiagent.domain.AiModelProviderType;
import com.voltcanary.aiagent.llm.adapter.AiResolvedModel;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SpringAiExchangeTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final JsonNode schema = mapper.createObjectNode().put("type", "object")
            .set("properties", mapper.createObjectNode().set("answer", mapper.createObjectNode().put("type", "string")));

    @Test
    void isolatesCredentialsModelsAndDialogueAndUsesNativeStructuredOutput() throws Exception {
        var bodies = new ArrayList<JsonNode>();
        var keys = new ArrayList<String>();
        var server = server("/v1/chat/completions", exchange -> {
            bodies.add(mapper.readTree(exchange.getRequestBody()));
            keys.add(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, completion("stop", "{\"answer\":\"ready\"}", null));
        });
        try {
            var observations = ObservationRegistry.create();
            var names = new ArrayList<String>();
            observations.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
                @Override public boolean supportsContext(Observation.Context context) { return true; }
                @Override public void onStop(Observation.Context context) { names.add(context.getName()); }
            });
            var ai = new SpringAiExchange(mapper, observations);
            var first = ai.call(model(server, AiModelProviderType.OPENAI, "account-a", "a-secret"),
                    "server instructions", "{\"pendingQuestion\":\"Имя?\",\"answer\":\"Трамвай\"}", schema, false, 3000);
            var second = ai.call(model(server, AiModelProviderType.OPENAI_COMPATIBLE, "account-b", "b-secret"),
                    "server instructions", "other conversation", null, false, 3000);
            assertNull(first.failure());
            assertNull(second.failure());
            assertEquals(List.of("Bearer a-secret", "Bearer b-secret"), keys);
            assertEquals("account-a", bodies.get(0).path("model").asText());
            assertEquals("account-b", bodies.get(1).path("model").asText());
            assertTrue(bodies.get(0).at("/messages/1/content").asText().contains("Трамвай"));
            assertEquals("other conversation", bodies.get(1).at("/messages/1/content").asText());
            assertEquals("json_schema", bodies.get(0).at("/response_format/type").asText());
            assertFalse(bodies.get(1).has("response_format"));
            assertEquals(20, first.usage().inputTokens());
            assertEquals(7, first.usage().outputTokens());
            assertEquals(3, first.usage().cachedInputTokens());
            assertEquals(2, first.usage().reasoningTokens());
            assertTrue(names.stream().anyMatch(name -> name.startsWith("spring.ai.chat.client")), names.toString());
            assertTrue(names.stream().anyMatch(name -> name.startsWith("gen_ai.client")), names.toString());
        } finally { server.stop(0); }
    }

    @Test
    void compatibleProviderRetainsCustomBaseUrlPrefix() throws Exception {
        var calls = new AtomicInteger();
        var server = server("/custom/chat/completions", exchange -> {
            calls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            respond(exchange, 200, completion("stop", "{\"answer\":\"ok\"}", null));
        });
        try {
            var selected = model(server, AiModelProviderType.DEEPSEEK, "configured", "fixture");
            selected.provider().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/custom");
            var result = new SpringAiExchange(mapper).call(selected, "system", "input", null, false, 3000);
            assertNull(result.failure());
            assertEquals(1, calls.get());
        } finally { server.stop(0); }
    }

    @Test
    void returnsToolProposalWithoutExecutingItOrMakingAnotherModelCall() throws Exception {
        var calls = new AtomicInteger();
        var server = server("/v1/chat/completions", exchange -> {
            calls.incrementAndGet();
            var body = mapper.readTree(exchange.getRequestBody());
            assertEquals("assistant_proposal", body.at("/tools/0/function/name").asText());
            respond(exchange, 200, completion("tool_calls", null, Map.of("id", "proposal-1", "type", "function",
                    "function", Map.of("name", "assistant_proposal", "arguments", "{\"answer\":\"review\"}"))));
        });
        try {
            var result = new SpringAiExchange(mapper).call(model(server, AiModelProviderType.OPENAI, "pinned", "fixture"),
                    "Propose only", "Create a device", schema, true, 3000);
            assertNull(result.failure());
            assertEquals("review", mapper.readTree(result.text()).path("answer").asText());
            assertEquals(1, calls.get());
        } finally { server.stop(0); }
    }

    @Test
    void serverErrorsAreNeverRetried() throws Exception {
        var calls = new AtomicInteger();
        var server = server("/v1/chat/completions", exchange -> {
            calls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            respond(exchange, 503, Map.of("error", Map.of("message", "fixture unavailable", "type", "server_error")));
        });
        try {
            var failure = assertThrows(Exception.class, () -> new SpringAiExchange(mapper).call(
                    model(server, AiModelProviderType.OPENAI, "pinned", "fixture"), "system", "input", null, false, 3000));
            assertEquals(503, SpringAiFailures.status(failure));
            assertEquals(1, calls.get());
        } finally { server.stop(0); }
    }

    @Test
    void dashboardToolsUseSdkToolCallingWithoutExecutionOrContinuation() throws Exception {
        for (String name : List.of("prepare_dashboard_creation", "prepare_dashboard_refinement", "prepare_dashboard_update")) {
            var calls = new AtomicInteger();
            var bodies = new ArrayList<JsonNode>();
            var server = server("/v1/chat/completions", exchange -> {
                calls.incrementAndGet();
                var body = mapper.readTree(exchange.getRequestBody());
                bodies.add(body);
                assertEquals(1, body.path("tools").size());
                assertEquals(name, body.at("/tools/0/function/name").asText());
                assertEquals("required", body.path("tool_choice").asText());
                assertFalse(body.path("parallel_tool_calls").asBoolean(true));
                assertFalse(body.has("response_format"));
                respond(exchange, 200, completion("tool_calls", null, Map.of("id", "review-1", "type", "function",
                        "function", Map.of("name", name, "arguments", "{\"answer\":\"prepared\"}"))));
            });
            try {
                var result = new SpringAiExchange(mapper).call(model(server, AiModelProviderType.OPENAI, "pinned", "fixture"),
                        "Prepare review", "Move current speed above history", schema, true, name, 3000);
                assertNull(result.failure());
                assertEquals("prepared", mapper.readTree(result.text()).path("answer").asText());
                assertEquals(1, calls.get());
                assertTrue(bodies.getFirst().at("/tools/0/function/strict").asBoolean());
                var callback = new com.voltcanary.aiagent.adapter.springai.ReviewedProposalTool(name, schema.toString());
                assertThrows(IllegalStateException.class, () -> callback.call("{}"));
            } finally { server.stop(0); }
        }
    }

    @Test
    void dashboardToolsRejectWrongNamesMultipleCallsAndTextWithUsageRetained() throws Exception {
        var ai = new SpringAiExchange(mapper);
        for (var message : List.of(
                org.springframework.ai.chat.messages.AssistantMessage.builder().content("").toolCalls(List.of(
                        new org.springframework.ai.chat.messages.AssistantMessage.ToolCall("1", "function", "prepare_dashboard_creation", "{}"))).build(),
                org.springframework.ai.chat.messages.AssistantMessage.builder().content("").toolCalls(List.of(
                        new org.springframework.ai.chat.messages.AssistantMessage.ToolCall("1", "function", "prepare_dashboard_update", "{}"),
                        new org.springframework.ai.chat.messages.AssistantMessage.ToolCall("2", "function", "prepare_dashboard_update", "{}"))).build(),
                new org.springframework.ai.chat.messages.AssistantMessage("{\"answer\":\"prose\"}"))) {
            var response = org.springframework.ai.chat.model.ChatResponse.builder()
                    .generations(List.of(new org.springframework.ai.chat.model.Generation(message)))
                    .metadata(org.springframework.ai.chat.metadata.ChatResponseMetadata.builder()
                            .usage(new org.springframework.ai.chat.metadata.DefaultUsage(20, 7)).build()).build();
            var result = ai.decode(response, true, "prepare_dashboard_update");
            assertEquals("PROVIDER_TOOL_INVALID", result.failure());
            assertEquals("", result.text());
            assertEquals(20, result.usage().inputTokens());
        }
        assertThrows(IllegalArgumentException.class, () ->
                new com.voltcanary.aiagent.adapter.springai.ReviewedProposalTool("execute_anything", schema.toString()));
    }

    @Test
    void nativeAnthropicAndGeminiReturnDashboardToolArgumentsWithoutContinuation() throws Exception {
        String name = "prepare_dashboard_update";
        for (var type : List.of(AiModelProviderType.ANTHROPIC, AiModelProviderType.GEMINI)) {
            var calls = new AtomicInteger();
            var bodies = new ArrayList<JsonNode>();
            var server = server("/", exchange -> {
                calls.incrementAndGet();
                bodies.add(mapper.readTree(exchange.getRequestBody()));
                if (type == AiModelProviderType.ANTHROPIC) {
                    respond(exchange, 200, Map.of("id", "review-1", "type", "message", "role", "assistant", "model", "fixture",
                            "stop_reason", "tool_use", "content", List.of(Map.of("type", "tool_use", "id", "tool-1", "name", name,
                            "input", Map.of("answer", "prepared"))), "usage", Map.of("input_tokens", 20, "output_tokens", 7)));
                } else {
                    respond(exchange, 200, Map.of("modelVersion", "fixture", "candidates", List.of(Map.of("index", 0, "finishReason", "STOP",
                            "content", Map.of("role", "model", "parts", List.of(Map.of("functionCall", Map.of("name", name,
                            "args", Map.of("answer", "prepared"))))))),
                            "usageMetadata", Map.of("promptTokenCount", 20, "candidatesTokenCount", 7, "totalTokenCount", 27)));
                }
            });
            try {
                var result = new SpringAiExchange(mapper).call(model(server, type, "fixture", "fixture-key"),
                        "Prepare review", "Move speed up", schema, true, name, 3000);
                assertNull(result.failure(), type.name());
                assertEquals("prepared", mapper.readTree(result.text()).path("answer").asText());
                assertEquals(1, calls.get());
                assertEquals(20, result.usage().inputTokens());
                var body = bodies.getFirst();
                if (type == AiModelProviderType.ANTHROPIC) {
                    assertEquals(name, body.at("/tools/0/name").asText());
                    assertEquals("any", body.at("/tool_choice/type").asText());
                    assertTrue(body.at("/tool_choice/disable_parallel_tool_use").asBoolean());
                } else {
                    assertEquals(name, body.at("/tools/0/functionDeclarations/0/name").asText());
                    assertEquals("ANY", body.at("/toolConfig/functionCallingConfig/mode").asText());
                }
            } finally { server.stop(0); }
        }
    }

    @Test
    void nativeProviderErrorsAreNeverRetried() throws Exception {
        for (var type : List.of(AiModelProviderType.ANTHROPIC, AiModelProviderType.GEMINI)) {
            var calls = new AtomicInteger();
            var server = server("/", exchange -> {
                calls.incrementAndGet();
                exchange.getRequestBody().readAllBytes();
                respond(exchange, 503, Map.of("error", Map.of("code", 503, "message", "fixture unavailable", "status", "UNAVAILABLE", "type", "overloaded_error")));
            });
            try {
                var selected = model(server, type, "fixture", "fixture-key");
                var failure = assertThrows(Exception.class, () -> new SpringAiExchange(mapper).call(selected, "system", "input", null, false, 3000));
                assertEquals(503, SpringAiFailures.status(failure));
                assertEquals(1, calls.get(), type.name());
            } finally { server.stop(0); }
        }
    }

    @Test
    void incompleteOutputsRetainUsageAndHidePartialText() throws Exception {
        var server = server("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            respond(exchange, 200, completion("length", "private partial output", null));
        });
        try {
            var result = new SpringAiExchange(mapper).call(model(server, AiModelProviderType.OPENAI, "pinned", "fixture"),
                    "system", "input", null, false, 3000);
            assertEquals("PROVIDER_RESPONSE_INCOMPLETE", result.failure());
            assertEquals("", result.text());
            assertEquals(7, result.usage().outputTokens());
        } finally { server.stop(0); }
    }

    @Test
    void durableHistoryBecomesTypedMessagesWithoutDuplicatingTextOrLosingPendingQuestion() throws Exception {
        var input = mapper.createObjectNode().put("kind", "planner.request").put("message", "Трамвай");
        var dialogue = input.putObject("dialogue");
        dialogue.putArray("messages").addObject().put("messageId", "prior-user").put("role", "USER").put("text", "Создай устройство");
        dialogue.withArray("messages").addObject().put("messageId", "question").put("role", "ASSISTANT").put("text", "Как назвать?");
        dialogue.putObject("pendingQuestion").put("assistantMessageId", "question").put("text", "Как назвать?");
        var messages = com.voltcanary.aiagent.adapter.springai.SpringAiConversationContext.messages(mapper, "trusted", input.toString());
        assertEquals(4, messages.size());
        assertEquals(org.springframework.ai.chat.messages.MessageType.SYSTEM, messages.getFirst().getMessageType());
        assertEquals(org.springframework.ai.chat.messages.MessageType.USER, messages.get(1).getMessageType());
        assertEquals(org.springframework.ai.chat.messages.MessageType.ASSISTANT, messages.get(2).getMessageType());
        var current = mapper.readTree(messages.getLast().getText());
        assertEquals("Трамвай", current.path("message").asText());
        assertEquals("Как назвать?", current.at("/dialogue/pendingQuestion/text").asText());
        assertFalse(current.at("/dialogue/messages/0").has("text"));
        assertEquals("Создай устройство", input.at("/dialogue/messages/0/text").asText());
    }

    @Test
    void geminiUsesNativeGenerateContentAndConfiguredBaseUrl() throws Exception {
        var server = server("/v1beta/models/gemini-fixture:generateContent", exchange -> {
            assertEquals("gemini-fixture-key", exchange.getRequestHeaders().getFirst("x-goog-api-key"));
            var body = mapper.readTree(exchange.getRequestBody());
            assertTrue(body.path("contents").isArray());
            respond(exchange, 200, Map.of("modelVersion", "gemini-fixture", "candidates", List.of(Map.of("index", 0, "finishReason", "STOP",
                    "content", Map.of("role", "model", "parts", List.of(Map.of("text", "{\"answer\":\"ok\"}"))))),
                    "usageMetadata", Map.of("promptTokenCount", 20, "candidatesTokenCount", 7, "totalTokenCount", 27)));
        });
        try {
            var selected = model(server, AiModelProviderType.GEMINI, "gemini-fixture", "gemini-fixture-key");
            selected.provider().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            var result = new SpringAiExchange(mapper).call(selected, "system", "input", null, false, 3000);
            assertNull(result.failure());
            assertEquals("ok", mapper.readTree(result.text()).path("answer").asText());
        } finally { server.stop(0); }
    }

    @Test
    void anthropicUsesItsNativeEndpointAndConfiguredCredentials() throws Exception {
        var server = server("/v1/messages", exchange -> {
            assertEquals("claude-fixture", mapper.readTree(exchange.getRequestBody()).path("model").asText());
            assertEquals("anthropic-fixture", exchange.getRequestHeaders().getFirst("x-api-key"));
            respond(exchange, 200, Map.of("id", "message-1", "type", "message", "role", "assistant", "model", "claude-fixture",
                    "stop_reason", "end_turn", "content", List.of(Map.of("type", "text", "text", "{\"answer\":\"ok\"}")),
                    "usage", Map.of("input_tokens", 20, "output_tokens", 7)));
        });
        try {
            var result = new SpringAiExchange(mapper).call(model(server, AiModelProviderType.ANTHROPIC, "claude-fixture", "anthropic-fixture"),
                    "system", "input", null, false, 3000);
            assertNull(result.failure());
            assertEquals("ok", mapper.readTree(result.text()).path("answer").asText());
        } finally { server.stop(0); }
    }

    private AiResolvedModel model(HttpServer server, AiModelProviderType type, String name, String key) {
        var provider = new AiModelProvider();
        provider.setProviderType(type);
        provider.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + (type == AiModelProviderType.ANTHROPIC ? "" : "/v1"));
        var config = new AiModelConfig();
        config.setModelId(name);
        config.setMaxOutputTokens(100);
        return new AiResolvedModel(provider, config, key);
    }

    private HttpServer server(String path, com.sun.net.httpserver.HttpHandler handler) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(path, handler);
        server.start();
        return server;
    }

    private Map<String, Object> completion(String finish, String text, Map<String, Object> tool) {
        var message = mapper.createObjectNode().put("role", "assistant");
        if (text != null) message.put("content", text);
        if (tool != null) message.set("tool_calls", mapper.valueToTree(List.of(tool)));
        return Map.of("id", "fixture", "object", "chat.completion", "created", 1, "model", "fixture",
                "choices", List.of(Map.of("index", 0, "finish_reason", finish, "message", message)),
                "usage", Map.of("prompt_tokens", 20, "completion_tokens", 7, "total_tokens", 27,
                        "prompt_tokens_details", Map.of("cached_tokens", 3), "completion_tokens_details", Map.of("reasoning_tokens", 2)));
    }

    private void respond(com.sun.net.httpserver.HttpExchange exchange, int status, Object body) throws java.io.IOException {
        byte[] bytes = mapper.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
