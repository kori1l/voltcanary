package com.voltcanary.aiagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voltcanary.aiagent.adapter.PrimaryModelTransport;
import com.voltcanary.aiagent.domain.AiModelConfig;
import com.voltcanary.aiagent.llm.adapter.AiResolvedModel;
import com.voltcanary.aiagent.orchestration.contract.ProtocolValidator;
import com.voltcanary.aiagent.orchestration.routing.ModelProposalRejected;
import com.voltcanary.aiagent.service.DashboardSkillContract;
import com.voltcanary.aiagent.service.DashboardSkillGuide;
import com.voltcanary.aiagent.service.PrimarySkillPackages;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DashboardCreationPeriodWireTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ProtocolValidator validator = new ProtocolValidator(mapper);

    @Test void explicitGlobalPeriodLowersBeforeWidgetsAndDoesNotOverwriteChartPeriod() throws Exception {
        for (String mode : List.of("JSON_TEXT", "NATIVE_TOOLS", "STRICT_JSON")) {
            var skill = PrimarySkillPackages.build(mapper, validator, List.of()).stream()
                    .filter(s -> s.manifest().path("intentId").asText().equals(DashboardSkillContract.CREATE)).findFirst().orElseThrow();
            var schema = skill.schemas().get(skill.manifest().path("outputSchemaId").asText());
            var config = new AiModelConfig();
            config.setModelId("fixture");
            var transport = new PrimaryModelTransport(mapper, new AiResolvedModel(null, config, "unused"), null, null);
            var request = mapper.createObjectNode().put("kind", "step.request").put("intentId", DashboardSkillContract.CREATE);
            String body = transport.serialize(request, schema, mode);
            if (!mode.equals("JSON_TEXT")) {
                var wireSchema = mapper.readTree(body).at(mode.equals("NATIVE_TOOLS") ? "/tools/0/parameters" : "/text/format/schema");
                int count = 0;
                for (String line : DashboardSkillGuide.render(mapper, DashboardSkillContract.CREATE).lines().toList()) {
                    if (!line.startsWith("{\"version\"")) continue;
                    validator.validateRegistered(wireSchema, mapper.createObjectNode().set("proposal", mapper.readTree(line)));
                    count++;
                }
                assertEquals(2, count);
            }
            var value = DashboardSkillGuide.examples(mapper, DashboardSkillContract.CREATE).get(1).path("response").<ObjectNode>deepCopy();
            var payload = (ObjectNode) value.at("/command/payload");
            payload.put("dashboardPeriod", "LAST_6_HOURS");
            for (var operation : payload.path("operations")) if (operation.path("op").asText().equals("show_history"))
                ((ObjectNode) operation).put("period", "LAST_1_HOUR");
            var before = value.deepCopy();
            if (!mode.equals("JSON_TEXT")) {
                var wireSchema = mapper.readTree(body).at(mode.equals("NATIVE_TOOLS") ? "/tools/0/parameters" : "/text/format/schema");
                validator.validateRegistered(wireSchema, mapper.createObjectNode().set("proposal", value));
            }
            var normalized = transport.normalize(value, body);
            validator.validateRegistered(schema, normalized);
            assertFalse(normalized.at("/command/payload").has("dashboardPeriod"));
            assertEquals("set_period", normalized.at("/command/payload/operations/0/op").asText());
            assertEquals("LAST_6_HOURS", normalized.at("/command/payload/operations/0/period").asText());
            assertTrue(normalized.at("/command/payload/operations/0/targets").isNull());
            assertTrue(normalized.at("/command/payload/operations").toString().contains("LAST_1_HOUR"));
            assertEquals(before, value);
            var correction = mapper.readTree(transport.serializeCorrection(request, schema, mode,
                    new ModelProposalRejected(normalized, "registered validation")));
            var correctedInput = mapper.readTree(correction.path("input").asText());
            assertEquals("LAST_6_HOURS", correctedInput.at("/formatCorrection/rejectedProposal/command/payload/dashboardPeriod").asText());
            assertEquals("rename_dashboard", correctedInput.at("/formatCorrection/rejectedProposal/command/payload/operations/0/op").asText());
            assertEquals(normalized, transport.normalize(correctedInput.at("/formatCorrection/rejectedProposal"), body));
            payload.remove("dashboardPeriod");
            assertThrows(ModelProposalRejected.class, () -> transport.normalize(value, body));
            payload.put("dashboardPeriod", "LAST_6_HOURS");
            payload.withArray("operations").addObject().put("op", "set_period").put("id", "conflicting")
                    .put("period", "LAST_7_DAYS").putNull("targets");
            assertThrows(ModelProposalRejected.class, () -> transport.normalize(value, body));
        }
    }
}
