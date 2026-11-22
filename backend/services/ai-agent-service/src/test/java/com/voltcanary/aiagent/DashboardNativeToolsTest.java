package com.voltcanary.aiagent;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.aiagent.adapter.PrimaryModelTransport;
import com.voltcanary.aiagent.adapter.springai.DashboardProposalTools;
import com.voltcanary.aiagent.adapter.springai.ReviewedProposalTool;
import com.voltcanary.aiagent.domain.AiModelConfig;
import com.voltcanary.aiagent.llm.adapter.AiResolvedModel;
import com.voltcanary.aiagent.orchestration.contract.ProtocolValidator;
import com.voltcanary.aiagent.service.DashboardSkillContract;
import com.voltcanary.aiagent.service.DashboardSkillGuide;
import com.voltcanary.aiagent.service.PrimarySkillPackages;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DashboardNativeToolsTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ProtocolValidator validator = new ProtocolValidator(mapper);

    @Test
    void selectsSeparateToolGuideAndSchemaAndRoundTripsEverySkillExample() throws Exception {
        var names = Map.of(
            DashboardSkillContract.CREATE,
            DashboardProposalTools.CREATE,
            DashboardSkillContract.REFINE,
            DashboardProposalTools.REFINE,
            DashboardSkillContract.UPDATE,
            DashboardProposalTools.UPDATE
        );
        var config = new AiModelConfig();
        config.setModelId("offline-fixture");
        var transport = new PrimaryModelTransport(
            mapper,
            new AiResolvedModel(null, config, "unused"),
            null,
            null
        );
        for (var skill : PrimarySkillPackages.build(mapper, validator, List.of())) {
            String intent = skill.manifest().path("intentId").asText();
            if (!names.containsKey(intent)) continue;
            var schema = skill.schemas().get(skill.manifest().path("outputSchemaId").asText());
            var request = mapper
                .createObjectNode()
                .put("kind", "step.request")
                .put("intentId", intent);
            request.set("instructions", skill.manifest().path("instructions"));
            request.set("contract", mapper.createObjectNode());
            var original = request.deepCopy();
            String serialized = transport.serialize(request, schema, "NATIVE_TOOLS");
            var body = mapper.readTree(serialized);
            assertEquals(names.get(intent), body.at("/tools/0/name").asText());
            assertEquals(names.get(intent), body.at("/tool_choice/name").asText());
            assertTrue(
                body.path("instructions").asText().contains(DashboardSkillGuide.purpose(intent))
            );
            assertEquals(original, request);
            for (var example : DashboardSkillGuide.examples(mapper, intent)) {
                var wire = mapper.createObjectNode().set("proposal", example.path("response"));
                var reply = transport.complete(
                    new com.voltcanary.aiagent.adapter.springai.SpringAiExchange.Result(
                        wire.toString(),
                        null,
                        null
                    ),
                    "NATIVE_TOOLS",
                    serialized
                );
                assertNull(reply.completionCode());
                var normalized = validator.parse(reply.proposalJson());
                validator.validateRegistered(schema, normalized);
                assertEquals(example.path("response"), normalized);
            }
        }
    }

    @Test
    void movementSizeAndSettingsUseCanonicalValidationAndRejectUnknownWidgetKeys() throws Exception {
        var payload = mapper.readTree(
            """
            {"protocol":"dashboard-operations-v1","operations":[
            {"op":"place_widgets","id":"top","targets":["speed_now"],"position":"TOP","anchor":null},
            {"op":"resize_widgets","id":"size","targets":["speed_now"],"width":"HALF","height":2,"heightPercent":null},
            {"op":"set_presentation","id":"style","targets":["speed_now"],"settings":[{"key":"showTitle","value":true}]}]}
            """
        );
        // Use the production protocol constant rather than a parallel test contract.
        ((com.fasterxml.jackson.databind.node.ObjectNode) payload).put(
            "protocol",
            com.voltcanary.aiagent.service.DashboardOperationProtocol.VERSION
        );
        var context = Map.<String, Object>of(
            "previousDashboardIntent",
            Map.of("widgets", List.of(Map.of("key", "speed_now")))
        );
        for (String intent : List.of(
            DashboardSkillContract.REFINE,
            DashboardSkillContract.UPDATE
        )) {
            DashboardSkillContract.validate(mapper, validator, intent, payload, context);
            assertEquals(
                payload,
                mapper.valueToTree(DashboardSkillContract.domainPayload(mapper, intent, payload))
            );
            var invalid = payload.deepCopy();
            (
                (com.fasterxml.jackson.databind.node.ArrayNode) invalid.at("/operations/0/targets")
            ).set(0, mapper.valueToTree("unknown"));
            assertThrows(IllegalArgumentException.class, () ->
                DashboardSkillContract.validate(mapper, validator, intent, invalid, context)
            );
        }
        assertThrows(IllegalArgumentException.class, () ->
            DashboardSkillContract.validate(
                mapper,
                validator,
                DashboardSkillContract.CREATE,
                payload,
                context
            )
        );
    }

    @Test
    void plannerAssessmentAndNonDashboardSkillsKeepGenericTool() {
        var request = mapper
            .createObjectNode()
            .put("kind", "planner.request")
            .put("intentId", DashboardSkillContract.CREATE);
        assertEquals(ReviewedProposalTool.NAME, DashboardProposalTools.select(request, false));
        request.put("kind", "step.request");
        assertEquals(ReviewedProposalTool.NAME, DashboardProposalTools.select(request, true));
        request.put("intentId", "CREATE_DEVICE");
        assertEquals(ReviewedProposalTool.NAME, DashboardProposalTools.select(request, false));
        assertNotEquals(
            DashboardSkillGuide.render(mapper, DashboardSkillContract.CREATE),
            DashboardSkillGuide.render(mapper, DashboardSkillContract.REFINE)
        );
        assertNotEquals(
            DashboardSkillGuide.render(mapper, DashboardSkillContract.UPDATE),
            DashboardSkillGuide.render(mapper, DashboardSkillContract.REFINE)
        );
    }
}
