package com.voltcanary.aiagent.llm.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.voltcanary.aiagent.domain.AiModelConfig;
import com.voltcanary.aiagent.domain.AiModelProvider;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OpenAiLongResponseTest {

    @Test
    void reportsProviderTokenExhaustionWithoutParsingOrExposingThePartialOutput() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] response = mapper.writeValueAsBytes(
                Map.of(
                    "id",
                    "fixture",
                    "object",
                    "chat.completion",
                    "created",
                    1,
                    "model",
                    "gpt-5-nano",
                    "choices",
                    java.util.List.of(
                        Map.of(
                            "index",
                            0,
                            "finish_reason",
                            "length",
                            "message",
                            Map.of("role", "assistant", "content", "private partial response")
                        )
                    )
                )
            );
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            AiModelProvider provider = new AiModelProvider();
            provider.setProviderType(com.voltcanary.aiagent.domain.AiModelProviderType.OPENAI);
            provider.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            AiModelConfig model = new AiModelConfig();
            model.setModelId("gpt-5-nano");
            var request = new AiModelPlanRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Compose",
                Map.of(),
                "Prepare",
                new AiResolvedModel(provider, model, "local-test")
            );
            var failure = assertThrows(IncompleteModelResponseException.class, () ->
                new OpenAiAdapter(mapper, new JsonPlanParser(mapper)).plan(request)
            );
            assertEquals("MODEL_OUTPUT_LIMIT", failure.getMessage());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void responseDeadlineStopsTheExchangeWithoutRetry() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var exchanges = new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/chat/completions", exchange -> {
            exchanges.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            try {
                Thread.sleep(1500);
                byte[] response = mapper.writeValueAsBytes(
                    Map.of(
                        "id",
                        "fixture",
                        "object",
                        "chat.completion",
                        "created",
                        1,
                        "model",
                        "gpt-5-nano",
                        "choices",
                        java.util.List.of(
                            Map.of(
                                "index",
                                0,
                                "finish_reason",
                                "stop",
                                "message",
                                Map.of(
                                    "role",
                                    "assistant",
                                    "content",
                                    "{\"summary\":\"Ready\",\"explanation\":\"Prepared\",\"actions\":[]}"
                                )
                            )
                        )
                    )
                );
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            AiModelProvider provider = new AiModelProvider();
            provider.setProviderType(com.voltcanary.aiagent.domain.AiModelProviderType.OPENAI);
            provider.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            AiModelConfig model = new AiModelConfig();
            model.setModelId("gpt-5-nano");
            model.setTimeoutMs(1000);
            var request = new AiModelPlanRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Compose",
                Map.of(),
                "Prepare a plan",
                new AiResolvedModel(provider, model, "local-test")
            );
            assertThrows(com.openai.errors.OpenAIIoException.class, () ->
                new OpenAiAdapter(mapper, new JsonPlanParser(mapper)).plan(request)
            );
            assertEquals(1, exchanges.get());
        } finally {
            server.stop(0);
        }
    }
}
