package com.voltcanary.aiagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.voltcanary.aiagent.adapter.PrimaryModelTransport;
import com.voltcanary.aiagent.domain.AiModelConfig;
import com.voltcanary.aiagent.domain.AiModelProvider;
import com.voltcanary.aiagent.domain.AiModelProviderType;
import com.voltcanary.aiagent.llm.adapter.AiAgentModelRunRecorder;
import com.voltcanary.aiagent.llm.adapter.AiResolvedModel;
import com.voltcanary.aiagent.orchestration.collaboration.ConsultationPrompt;
import com.voltcanary.aiagent.orchestration.collaboration.Consultations;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol;
import com.voltcanary.aiagent.orchestration.contract.ProtocolValidator;
import com.voltcanary.aiagent.orchestration.routing.ModelRouting;
import com.voltcanary.aiagent.security.AuthenticatedUser;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ConsultationTransportTest {
    @Test
    void specialistEnvelopeUsesExistingSpringAiSingleExchangeInAllThreeOutputModes() throws Exception {
        var mapper = new ObjectMapper();
        var validator = new ProtocolValidator(mapper);
        var counter = new AtomicInteger();
        var role = new Consultations.Role("schema", "1", "result-v1", Consultations.Reason.SCHEMA_REASONING,
                mapper.createObjectNode().put("type", "object").put("additionalProperties", false),
                Set.of(), Set.of(), Set.of(), "Only assess supplied metadata.");
        for (String mode : List.of("JSON_TEXT", "STRICT_JSON", "NATIVE_TOOLS")) {
            var answer = mapper.createObjectNode().put("outcome", "ANSWER").put("taskId", UUID.randomUUID().toString())
                    .put("attemptId", UUID.randomUUID().toString()).put("requirementsRevision", 1).put("readSetDigest", "a".repeat(64));
            answer.putObject("payload");
            answer.putArray("evidenceRefs");
            answer.putArray("unresolvedSlotIds");
            var wire = mapper.createObjectNode();
            wire.set("proposal", answer.deepCopy());
            ((com.fasterxml.jackson.databind.node.ObjectNode) wire.path("proposal")).put("payload", "{}");
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/chat/completions", exchange -> {
                counter.incrementAndGet();
                var body = mapper.readTree(exchange.getRequestBody());
                assertThat(body.at("/messages/0/content").asText()).contains("read-only specialist").doesNotContain("PREPARED requires");
                var message = mapper.createObjectNode().put("role", "assistant");
                if (mode.equals("NATIVE_TOOLS")) {
                    assertThat(body.at("/tools/0/function/name").asText()).isEqualTo("assistant_proposal");
                    message.set("tool_calls", mapper.valueToTree(List.of(Map.of("id", "fixture", "type", "function", "function",
                            Map.of("name", "assistant_proposal", "arguments", wire.toString())))));
                } else message.put("content", mode.equals("JSON_TEXT") ? answer.toString() : wire.toString());
                byte[] bytes = mapper.writeValueAsBytes(Map.of("id", "fixture", "object", "chat.completion", "created", 1, "model", "fixture",
                        "choices", List.of(Map.of("index", 0, "finish_reason", mode.equals("NATIVE_TOOLS") ? "tool_calls" : "stop", "message", message)),
                        "usage", Map.of("prompt_tokens", 20, "completion_tokens", 7, "total_tokens", 27)));
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            try {
                var provider = new AiModelProvider();
                provider.setProviderType(AiModelProviderType.OPENAI);
                provider.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
                var config = new AiModelConfig();
                config.setModelId("fixture");
                config.setMaxOutputTokens(1000);
                var transport = new PrimaryModelTransport(mapper, new AiResolvedModel(provider, config, "fixture-only"),
                        new AuthenticatedUser(UUID.randomUUID(), "fixture@example.invalid", "USER"), mock(AiAgentModelRunRecorder.class));
                var request = mapper.createObjectNode().put("kind", "consultation.assignment").put("planId", UUID.randomUUID().toString())
                        .put("instructions", role.instructions()).put("taskId", answer.path("taskId").asText());
                String body = transport.serialize(request, ConsultationPrompt.responseSchema(validator, role), mode);
                var candidate = new AssistantProtocol.Candidate("provider", "model", null, 128_000L, 112_000L, 1000L, mode, null);
                var reply = transport.send(body, new ModelRouting.Route(candidate, ModelRouting.estimate(body, 1000, 4096), 100, null, "PINNED_EXPLICIT"), 5000);
                assertThat(reply.completionCode()).isNull();
                validator.validate("collab.WorkerResponse", mapper.readTree(reply.proposalJson()));
                assertThat(mapper.readTree(reply.proposalJson())).isEqualTo(answer);
                assertThat(reply.usage().inputTokens()).isEqualTo(20);
            } finally {
                server.stop(0);
            }
        }
        assertThat(counter).hasValue(3);
    }
}
