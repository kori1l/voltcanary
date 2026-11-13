package com.voltcanary.aiagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol;
import com.voltcanary.aiagent.orchestration.contract.ProtocolValidator;
import com.voltcanary.aiagent.orchestration.contract.SkillRegistry;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssistantProtocolScenarioTest {
    @Test
    void canonicalDialogueAndUntrustedProposalScenario() throws Exception {
        var mapper = new ObjectMapper();
        var validator = new ProtocolValidator(mapper);
        JsonNode cases;
        try (var input = getClass().getResourceAsStream("/assistant-v2/ai-assistant-protocol.examples.json")) {
            cases = mapper.readTree(input).path("cases");
        }
        for (var fixture : cases) {
            String definition = fixture.path("schema").asText();
            var document = fixture.path("document");
            validator.validate(definition, document);
            String name = Character.toUpperCase(definition.charAt(0)) + definition.substring(1);
            Class<?> type = Class.forName(AssistantProtocol.class.getName() + "$" + name);
            Object typed = validator.read(definition, document, type);
            assertEquals(document, mapper.readTree(mapper.writeValueAsString(typed)), fixture.path("name").asText());
            if (document.has("version")) {
                var incompatible = document.deepCopy();
                ((ObjectNode) incompatible).put("version", "assistant-v900");
                assertThrows(IllegalArgumentException.class, () -> validator.validate(definition, incompatible));
            }
        }
        var shortReply = example(cases, "short answer carries exact pending question and original request").deepCopy();
        var request = (ObjectNode) shortReply;
        assertFalse(request.at("/context/dialogue/pendingQuestion/question/text").asText().isBlank());
        request.remove("message");
        assertThrows(IllegalArgumentException.class, () -> validator.validate("stepRequest", request));
        request.set("message", mapper.valueToTree("второй"));
        ((ObjectNode) request.path("context")).remove("dialogue");
        assertThrows(IllegalArgumentException.class, () -> validator.validate("stepRequest", request));

        var manifest = (ObjectNode) example(cases, "server-owned skill manifest").deepCopy();
        ((ObjectNode) manifest.path("instructions")).put("digest", SkillRegistry.digest(manifest.at("/instructions/text").asText()));
        var registry = new SkillRegistry(validator, List.of(new SkillRegistry.Package(manifest, Map.of(
                manifest.path("inputSchemaId").asText(), mapper.readTree("{\"type\":\"object\"}"),
                manifest.path("outputSchemaId").asText(), validator.projection("stepResponse"),
                manifest.path("commandSchemaId").asText(), mapper.readTree("{\"type\":\"object\",\"additionalProperties\":false}")), Set.of("dashboard"),
                Map.of("dashboard.current-layout", manifest.path("inputSchemaId").asText(), "dashboard.data-choices", manifest.path("inputSchemaId").asText()))));
        String intent = manifest.path("intentId").asText();
        assertTrue(registry.catalogue(Set.of(intent), Set.of()).isEmpty());
        var summary = registry.catalogue(Set.of(intent), Set.of("dashboard"));
        assertEquals(1, summary.size());
        assertFalse(mapper.valueToTree(summary).toString().contains("instructions"));
        registry.validateParameters(intent, mapper.readTree("{\"targets\":[\"widget-1\"]}"), "MODEL_PROPOSED", Set.of(intent), Set.of("dashboard"));
        registry.validateParameters(intent, mapper.readTree("{\"targets\":[\"widget-1\"],\"position\":null}"), "MODEL_PROPOSED", Set.of(intent), Set.of("dashboard"));
        assertThrows(IllegalArgumentException.class, () -> registry.validateParameters(intent, mapper.readTree("{}"), "MODEL_PROPOSED", Set.of(intent), Set.of("dashboard")));
        assertThrows(IllegalArgumentException.class, () -> registry.validateParameters(intent, mapper.readTree("{\"targets\":[],\"position\":\"LEFT\"}"), "MODEL_PROPOSED", Set.of(intent), Set.of("dashboard")));
        assertThrows(IllegalArgumentException.class, () -> registry.require(intent, "unknown", "1", Set.of(intent), Set.of("dashboard")));
        var proposal = (ObjectNode) example(cases, "multi-intent dependency plan").deepCopy();
        assertThrows(IllegalArgumentException.class, () -> registry.validatePlanner(proposal, Set.of(intent), Set.of("dashboard")));
        var response = (ObjectNode) example(cases, "model proposes movement only").deepCopy();
        response.put("executed", true);
        assertThrows(IllegalArgumentException.class, () -> validator.validate("stepResponse", response));
        response.remove("executed");
        response.put("outcome", "SUCCEEDED");
        assertThrows(IllegalArgumentException.class, () -> validator.validate("stepResponse", response));
        assertThrows(IllegalArgumentException.class, () -> validator.parse("{\"kind\":1,\"kind\":2}"));
        assertThrows(IllegalArgumentException.class, () -> validator.parse("{} {}"));
    }

    private static JsonNode example(JsonNode cases, String name) {
        for (var fixture : cases) if (fixture.path("name").asText().equals(name)) return fixture.path("document");
        throw new IllegalStateException(name);
    }
}
