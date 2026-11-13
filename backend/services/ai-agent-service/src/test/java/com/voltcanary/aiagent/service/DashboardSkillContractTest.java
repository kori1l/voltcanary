package com.voltcanary.aiagent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voltcanary.aiagent.orchestration.contract.ProtocolValidator;
import com.voltcanary.aiagent.orchestration.contract.SkillRegistry;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DashboardSkillContractTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ProtocolValidator validator = new ProtocolValidator(mapper);
    private final Set<String> intents = Set.of(DashboardSkillContract.CREATE, DashboardSkillContract.REFINE, DashboardSkillContract.UPDATE);

    @Test void examplesValidateAgainstTheirSeparateVersionedContracts() {
        var registry = new SkillRegistry(validator, PrimarySkillPackages.build(mapper, validator, List.of()));
        for (String intent : intents) {
            assertEquals("2", registry.manifest(intent, intents, Set.of()).skillVersion());
            assertEquals(!intent.equals(DashboardSkillContract.CREATE),
                    registry.inputSchema(intent, intents, Set.of()).path("properties").has("widgetKeys"));
            for (var example : DashboardSkillGuide.examples(mapper, intent)) {
                var response = example.path("response");
                validator.validateRegistered(registry.responseProjection(intent, intents, Set.of()), response);
                registry.validateCommand(intent, response.path("command"), intents, Set.of());
            }
        }
    }

    @Test void creationOmitsWidgetKeysRequiresOneNameAndUsesOnlyEarlierLocalReferences() throws Exception {
        var schema = DashboardSkillContract.schema(mapper, DashboardSkillContract.CREATE);
        assertFalse(schema.toString().contains("widgetKey"));
        var payload = DashboardSkillGuide.examples(mapper, DashboardSkillContract.CREATE).get(1).at("/response/command/payload");
        DashboardSkillContract.validate(mapper, validator, DashboardSkillContract.CREATE, payload, Map.of());
        for (var value : List.of(mapper.nullNode(), mapper.valueToTree("CURRENT_SPEED"))) {
            var invalid = payload.deepCopy();
            ((ObjectNode) invalid.at("/operations/3")).set("widgetKey", value);
            assertThrows(IllegalArgumentException.class, () -> DashboardSkillContract.validate(mapper, validator, DashboardSkillContract.CREATE, invalid, Map.of()));
        }
        var unnamed = payload.deepCopy();
        ((com.fasterxml.jackson.databind.node.ArrayNode) unnamed.path("operations")).remove(0);
        assertThrows(IllegalArgumentException.class, () -> validator.validateRegistered(schema, unnamed));
        var twice = payload.deepCopy();
        ((com.fasterxml.jackson.databind.node.ArrayNode) twice.path("operations")).add(payload.at("/operations/0"));
        assertThrows(IllegalArgumentException.class, () -> validator.validateRegistered(schema, twice));
        var forward = payload.deepCopy();
        ((ObjectNode) forward.at("/operations/4")).put("target", "@future");
        assertEquals("UNKNOWN_OR_FORWARD_LOCAL_WIDGET_REF", assertThrows(IllegalArgumentException.class,
                () -> DashboardSkillContract.validate(mapper, validator, DashboardSkillContract.CREATE, forward, Map.of())).getMessage());
        var normalized = mapper.valueToTree(DashboardSkillContract.domainPayload(mapper, DashboardSkillContract.CREATE, payload));
        assertTrue(normalized.at("/operations/3/widgetKey").isNull());
        assertEquals("@speed", normalized.at("/operations/4/widgetKey").asText());
        assertFalse(normalized.at("/operations/4").has("target"));
        assertDoesNotThrow(() -> DashboardOperationProtocol.parse(DashboardSkillContract.domainPayload(mapper, DashboardSkillContract.CREATE, payload), mapper));
    }

    @Test void editKeysAreSnapshotMembershipNotIdentifierShapeAndNeverBecomeCreations() throws Exception {
        var context = Map.<String, Object>of("previousDashboardIntent", Map.of("widgets", List.of(Map.of("key", "CURRENT_SPEED"))));
        var payload = mapper.readTree("""
                {"protocol":"dashboard-operations-v1","operations":[
                {"op":"place_widgets","id":"top","targets":["CURRENT_SPEED"],"position":"TOP","anchor":null}]}
                """);
        for (String intent : List.of(DashboardSkillContract.REFINE, DashboardSkillContract.UPDATE)) {
            assertDoesNotThrow(() -> DashboardSkillContract.validate(mapper, validator, intent, payload, context));
            for (String invalid : List.of("foreign", "00000000-0000-0000-0000-000000000000", "@future")) {
                var changed = payload.deepCopy();
                ((ObjectNode) changed.at("/operations/0")).putArray("targets").add(invalid);
                assertThrows(IllegalArgumentException.class, () -> DashboardSkillContract.validate(mapper, validator, intent, changed, context));
                assertEquals(invalid, changed.at("/operations/0/targets/0").asText());
            }
        }
        assertFalse(DashboardSkillContract.schema(mapper, DashboardSkillContract.UPDATE).toString().contains("restore_review"));
        assertTrue(DashboardSkillContract.schema(mapper, DashboardSkillContract.REFINE).toString().contains("restore_review"));
    }

    @Test void creationExamplesLowerThroughExistingCompilerWithIndependentRoleAndOrderAssertions() throws Exception {
        var context = new DashboardOperationCompilerTest().context(false);
        for (var example : DashboardSkillGuide.examples(mapper, DashboardSkillContract.CREATE)) {
            var payload = mapper.readTree(example.at("/response/command/payload").toString()
                    .replace("speed_ref", "data_speedKmh").replace("position_ref", "data_position"));
            DashboardSkillContract.validate(mapper, validator, DashboardSkillContract.CREATE, payload, context.context());
            var envelope = DashboardOperationProtocol.parse(DashboardSkillContract.domainPayload(mapper, DashboardSkillContract.CREATE, payload), mapper);
            var compiled = DashboardOperationCompiler.compile(envelope, context, "persisted-turn");
            assertEquals(compiled.intent(), DashboardOperationCompiler.compile(envelope, context, "persisted-turn").intent());
            var widgets = mapper.valueToTree(compiled.intent()).path("widgets");
            assertEquals("vehicle-value", widgets.get(0).path("type").asText());
            if (widgets.size() > 1) {
                assertEquals(3, widgets.size());
                assertEquals("Скорость сейчас", widgets.get(0).path("title").asText());
                assertEquals("vehicle-map", widgets.get(1).path("type").asText());
                assertEquals("vehicle-timeline", widgets.get(2).path("type").asText());
                assertEquals("line", widgets.get(2).path("visualization").asText());
            }
        }
    }
}
