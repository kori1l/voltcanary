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

    @Test void editToolRejectsFieldsFromOtherOperationBranches() throws Exception {
        for (var intent : List.of(DashboardSkillContract.REFINE, DashboardSkillContract.UPDATE)) {
            var skill = PrimarySkillPackages.build(mapper, validator, List.of()).stream()
                    .filter(pkg -> intent.equals(pkg.manifest().path("intentId").asText())).findFirst().orElseThrow();
            var config = new AiModelConfig();
            config.setModelId("fixture");
            var transport = new PrimaryModelTransport(mapper, new AiResolvedModel(null, config, "unused"), null, null);
            var request = mapper.createObjectNode().put("kind", "step.request").put("intentId", intent);
            request.putObject("context").put("baseReviewId", "accepted");
            request.putArray("loadResults").addObject().put("loaderId", "workflow.context").putObject("data")
                    .set("operationSchema", DashboardSkillContract.schema(mapper, intent,
                            java.util.Map.of("dashboardDataChoices", List.of(), "previousDashboardIntent",
                                    java.util.Map.of("widgets", List.of(java.util.Map.of("key", "map-card", "type", "vehicle-map"))))));
            request.withArray("loadResults").addObject().put("loaderId", "review.base").putObject("data")
                    .put("reviewId", "accepted").putArray("actions").addObject().put("tool", "CREATE_DASHBOARD")
                    .putObject("currentWidgetInventory").putArray("widgets").addObject().put("key", "map-card");
            var body = transport.serialize(request, skill.schemas().get(skill.manifest().path("outputSchemaId").asText()),
                    "NATIVE_TOOLS");
            var wireSchema = mapper.readTree(body).at("/tools/0/parameters");
            var reply = mapper.createObjectNode();
            var proposal = reply.putObject("proposal").put("version", "assistant-v2").put("kind", "step.response")
                    .put("outcome", "PREPARED").put("explanation", "Rename the dashboard");
            var rename = proposal.putObject("command").put("schemaId", intent + ":command:2").put("schemaVersion", "2")
                    .putObject("payload").put("protocol", "dashboard-operations-v1").putArray("operations").addObject()
                    .put("op", "rename_dashboard").put("id", "rename").put("name", "New title");
            assertDoesNotThrow(() -> validator.validateRegistered(wireSchema, reply));
            rename.putNull("widgetKey");
            assertThrows(ProtocolValidator.RegisteredViolation.class, () -> validator.validateRegistered(wireSchema, reply));
            var variants = wireSchema.at("/$defs/command/properties/payload/properties/operations/items/anyOf");
            var placement = java.util.stream.StreamSupport.stream(variants.spliterator(), false)
                    .filter(branch -> "place_widgets".equals(branch.at("/properties/op/enum/0").asText()))
                    .findFirst().orElseThrow();
            assertEquals("map-card", placement.at("/properties/targets/items/anyOf/0/enum/0").asText());
            assertEquals("widget:1", placement.at("/properties/targets/items/anyOf/0/enum/1").asText());
            assertTrue(placement.at("/properties/targets/items/anyOf/0/description").asText().contains("map-card=vehicle-map"));
            assertTrue(placement.at("/properties/targets/items/anyOf/1/description").asText().contains("earlier operation"));
            var settings = java.util.stream.StreamSupport.stream(variants.spliterator(), false)
                    .filter(branch -> "set_presentation".equals(branch.at("/properties/op/enum/0").asText()))
                    .findFirst().orElseThrow();
            assertTrue(settings.at("/properties/settings/items/description").asText().contains("null"));
            assertTrue(settings.at("/properties/settings/items/properties/key/description").asText().contains("showMapNotices"));
            var presentation = mapper.readTree("""
                    {"op":"set_presentation","id":"hide_titles","targets":["ALL"],
                     "settings":[{"key":"showTitle","value":false}]}
                    """);
            ((com.fasterxml.jackson.databind.node.ArrayNode) proposal.at("/command/payload/operations")).set(0, presentation);
            assertDoesNotThrow(() -> validator.validateRegistered(wireSchema, reply));
            ((com.fasterxml.jackson.databind.node.ArrayNode) presentation.path("targets")).add("map-card");
            assertThrows(ProtocolValidator.RegisteredViolation.class, () -> validator.validateRegistered(
                    request.at("/loadResults/0/data/operationSchema"), proposal.at("/command/payload")));
            var move = mapper.readTree("""
                    {"op":"place_widgets","id":"move","targets":["widget:1"],"position":"TOP","anchor":null}
                    """);
            ((com.fasterxml.jackson.databind.node.ArrayNode) proposal.at("/command/payload/operations")).set(0, move);
            validator.validateRegistered(wireSchema, reply);
            var lowered = transport.normalize(proposal, body);
            assertEquals("map-card", lowered.at("/command/payload/operations/0/targets/0").asText());
            validator.validateRegistered(request.at("/loadResults/0/data/operationSchema"), lowered.at("/command/payload"));
        }
    }

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
                var operations = wireSchema.at("/$defs/command/properties/payload/properties/operations/items/anyOf");
                var current = java.util.stream.StreamSupport.stream(operations.spliterator(), false)
                        .filter(branch -> "show_current".equals(branch.at("/properties/op/enum/0").asText()))
                        .findFirst().orElseThrow();
                assertTrue(current.path("description").asText().contains("separate show_history operation"));
                var rename = java.util.stream.StreamSupport.stream(operations.spliterator(), false)
                        .filter(branch -> "rename_dashboard".equals(branch.at("/properties/op/enum/0").asText()))
                        .findFirst().orElseThrow();
                var fields = new java.util.HashSet<String>();
                rename.path("properties").fieldNames().forEachRemaining(fields::add);
                assertEquals(java.util.Set.of("op", "id", "name"), fields);
                assertFalse(rename.path("additionalProperties").asBoolean(true));
                int count = 0;
                for (String line : DashboardSkillGuide.render(mapper, DashboardSkillContract.CREATE).lines().toList()) {
                    if (!line.startsWith("{\"version\"")) continue;
                    validator.validateRegistered(wireSchema, mapper.createObjectNode().set("proposal", mapper.readTree(line)));
                    count++;
                }
                assertEquals(3, count);
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
                    .put("period", "LAST_7_DAYS");
            assertThrows(ModelProposalRejected.class, () -> transport.normalize(value, body));
            payload.remove("dashboardPeriod");
            payload.withArray("operations").addObject().put("op", "set_period").put("id", "second_global")
                    .put("period", "LAST_1_HOUR").putNull("targets");
            var conflictingCorrection = mapper.readTree(transport.serializeCorrection(request, schema, mode,
                    new ModelProposalRejected(value, "conflicting global periods")));
            assertEquals(value, mapper.readTree(conflictingCorrection.path("input").asText())
                    .at("/formatCorrection/rejectedProposal"));
        }
    }
}
