package com.voltcanary.aiagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.aiagent.adapter.PrimaryModelTransport;
import com.voltcanary.aiagent.adapter.PrimaryOutputCapabilities;
import com.voltcanary.aiagent.domain.AiModelConfig;
import com.voltcanary.aiagent.llm.adapter.AiResolvedModel;
import com.voltcanary.aiagent.orchestration.contract.ProtocolValidator;
import com.voltcanary.aiagent.orchestration.routing.ModelRouting;
import com.voltcanary.aiagent.orchestration.routing.StrictOutputProjection;
import com.voltcanary.aiagent.service.PrimarySkillPackages;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PrimaryStrictOutputTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ProtocolValidator validator = new ProtocolValidator(mapper);

    @Test void unsupportedProviderSchemaMakesExactlyOneExchangeWithoutModeOrModelFallback() throws Exception {
        var exchanges = new java.util.concurrent.atomic.AtomicInteger();
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            exchanges.incrementAndGet();
            var request = mapper.readTree(exchange.getRequestBody());
            assertEquals("pinned-fixture", request.path("model").asText());
            assertTrue(request.at("/response_format/json_schema/strict").asBoolean());
            exchange.sendResponseHeaders(400, 0);
            exchange.getResponseBody().close();
        });
        server.start();
        try {
            var config = new AiModelConfig();
            config.setModelId("pinned-fixture");
            var provider = new com.voltcanary.aiagent.domain.AiModelProvider();
            provider.setProviderType(com.voltcanary.aiagent.domain.AiModelProviderType.OPENAI);
            provider.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            var transport = new PrimaryModelTransport(mapper, new AiResolvedModel(provider, config, "fixture"),
                    new com.voltcanary.aiagent.security.AuthenticatedUser(java.util.UUID.randomUUID(), "fixture@example.invalid", "USER"),
                    org.mockito.Mockito.mock(com.voltcanary.aiagent.llm.adapter.AiAgentModelRunRecorder.class));
            var route = org.mockito.Mockito.mock(ModelRouting.Route.class);
            org.mockito.Mockito.when(route.candidate()).thenReturn(new com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.Candidate(
                    "provider", "pinned", null, 128000L, 112000L, 1000L, "STRICT_JSON", null));
            String body = transport.serialize(mapper.createObjectNode().put("kind", "planner.request"), validator.projection("plannerResponse"), "STRICT_JSON");
            var failure = assertThrows(ModelRouting.Stop.class, () -> transport.send(body, route, 2000));
            assertEquals("PROVIDER_HTTP_400", failure.getMessage());
            assertEquals(1, exchanges.get());
        } finally { server.stop(0); }
    }

    @Test void projectsEveryRegisteredResponseAndPlannerWithoutChangingCanonicalValidation() throws Exception {
        var planner = new StrictOutputProjection(mapper, validator.projection("plannerResponse")).schema();
        assertEquals("object", planner.path("type").asText());
        checkSubset(planner);
        checkSubset(new StrictOutputProjection(mapper, validator.projection("assessmentResponse")).schema());
        for (var skill : PrimarySkillPackages.build(mapper, validator, List.of())) {
            String id = skill.manifest().path("outputSchemaId").asText();
            checkSubset(new StrictOutputProjection(mapper, skill.schemas().get(id)).schema());
        }
        var canonical = validator.projection("stepResponse");
        var projection = new StrictOutputProjection(mapper, canonical);
        var wire = mapper.readTree("""
                {"proposal":{"version":"assistant-v2","kind":"step.response","outcome":"PREPARED","explanation":"review",
                "command":{"schemaId":"fixture","schemaVersion":"1","payload":"{\\"name\\":\\"Трамвай\\"}"}}}
                """);
        var normalized = projection.normalize(wire);
        assertEquals("Трамвай", normalized.at("/command/payload/name").asText());
        validator.validate("stepResponse", normalized);
        ((com.fasterxml.jackson.databind.node.ObjectNode) normalized).put("unexpected", true);
        assertThrows(IllegalArgumentException.class, () -> validator.validate("stepResponse", normalized));
    }

    @Test void optionalWireNullIsOmittedButCanonicalRequiredNullIsRetained() throws Exception {
        var schema = mapper.readTree("""
                {"type":"object","properties":{"optional":{"type":"string"},"required":{"type":["string","null"]}},
                "required":["required"],"additionalProperties":false}
                """);
        var projection = new StrictOutputProjection(mapper, schema);
        var value = projection.normalize(mapper.readTree("{\"proposal\":{\"optional\":null,\"required\":null}}"));
        assertFalse(value.has("optional"));
        assertTrue(value.has("required"));
        validator.validateRegistered(schema, value);
        assertThrows(ModelRouting.Stop.class, () -> new StrictOutputProjection(mapper, mapper.readTree("{\"type\":\"object\",\"properties\":{}} ")).schema());
    }

    @Test void configuredModesAreExplicitAndRefusalTruncationAndMalformedOutputDoNotDowngrade() throws Exception {
        var config = new AiModelConfig();
        config.setModelId("pinned-fixture");
        var selected = new AiResolvedModel(null, config, "secret-never-serialized");
        assertEquals("JSON_TEXT", PrimaryOutputCapabilities.mode(selected));
        config.setSupportsJson(true);
        config.setResponseFormat("json_schema");
        assertEquals("STRICT_JSON", PrimaryOutputCapabilities.mode(selected));
        var transport = new PrimaryModelTransport(mapper, selected, null, null);
        var request = mapper.createObjectNode().put("kind", "planner.request");
        String body = transport.serialize(request, validator.projection("plannerResponse"), "STRICT_JSON");
        assertEquals("pinned-fixture", mapper.readTree(body).path("model").asText());
        assertTrue(mapper.readTree(body).at("/text/format/strict").asBoolean());
        assertFalse(body.contains("secret-never-serialized"));
        for (String mode : List.of("STRICT_JSON", "JSON_TEXT", "NATIVE_TOOLS")) {
            for (String code : List.of("PROVIDER_REFUSAL", "PROVIDER_RESPONSE_INCOMPLETE")) {
                var rejected = new com.voltcanary.aiagent.adapter.springai.SpringAiExchange.Result("private partial output", null, code);
                assertEquals(code, transport.complete(rejected, mode, body).completionCode());
                assertEquals(mode, transport.complete(rejected, mode, body).outputMode());
                assertEquals("", transport.complete(rejected, mode, body).proposalJson());
            }
        }
        var malformed = new com.voltcanary.aiagent.adapter.springai.SpringAiExchange.Result("broken", null, null);
        assertEquals("STRICT_OUTPUT_INVALID", transport.complete(malformed, "STRICT_JSON", body).completionCode());
        config.setSupportsTools(true);
        config.setResponseFormat("tool");
        assertEquals("NATIVE_TOOLS", PrimaryOutputCapabilities.mode(selected));
        String tools = transport.serialize(request, validator.projection("plannerResponse"), "NATIVE_TOOLS");
        assertTrue(mapper.readTree(tools).at("/tools/0/strict").asBoolean());
        config.setSupportsJson(false);
        config.setResponseFormat("json_schema");
        assertThrows(ModelRouting.Stop.class, () -> PrimaryOutputCapabilities.mode(selected));
    }

    private void checkSubset(JsonNode schema) {
        if (schema.isArray()) { schema.forEach(this::checkSubset); return; }
        if (!schema.isObject()) return;
        assertFalse(schema.has("oneOf"));
        assertFalse(schema.has("allOf"));
        assertFalse(schema.has("if"));
        if (schema.path("type").asText().equals("object")) {
            assertFalse(schema.path("additionalProperties").asBoolean(true));
            assertEquals(schema.path("properties").size(), schema.path("required").size());
        }
        schema.forEach(this::checkSubset);
    }
}
