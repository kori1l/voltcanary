package com.voltcanary.aiagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voltcanary.aiagent.adapter.PrimaryModelTransport;
import com.voltcanary.aiagent.adapter.PrimaryPromptProjection;
import com.voltcanary.aiagent.domain.AiModelConfig;
import com.voltcanary.aiagent.llm.adapter.AiResolvedModel;
import com.voltcanary.aiagent.orchestration.contract.ProtocolValidator;
import com.voltcanary.aiagent.orchestration.contract.SkillRegistry;
import com.voltcanary.aiagent.service.CurrentWidgetInventory;
import com.voltcanary.aiagent.service.DashboardSkillContract;
import com.voltcanary.aiagent.service.PrimarySkillPackages;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PrimaryPromptProjectionTest {
    @Test void dashboardWriterRetrievesOnceWithoutRemovingContractsOrLeakingHintsToAssessor() throws Exception {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var embeddings = new com.voltcanary.aiagent.port.CapabilityEmbeddings() {
            public String identity() { return "projection-test-space"; }
            public List<float[]> embed(List<String> texts) {
                calls.incrementAndGet();
                return texts.stream().map(text -> new float[]{1, 1}).toList();
            }
        };
        var config = new AiModelConfig();
        config.setModelId("fixture");
        var transport = new PrimaryModelTransport(mapper, new AiResolvedModel(null, config, "unused"), null, null,
                new com.voltcanary.aiagent.service.WidgetCatalogueSearch(mapper, embeddings));
        var request = fixture();
        request.put("message", "show notices");
        var catalogue = request.withObject("/loadResults/1/data/context").putArray("dashboardWidgetTypes");
        var widget = catalogue.addObject().put("type", "vehicle-map").put("description", "Coordinates");
        widget.putObject("capabilities").putArray("settingContracts").addObject()
                .put("key", "showMapNotices").put("label", "Show notices");
        var before = request.deepCopy();
        var validator = new ProtocolValidator(mapper);
        for (int i = 0; i < 2; i++) {
            var body = mapper.readTree(transport.serialize(request, validator.projection("stepResponse"), "JSON_TEXT"));
            var input = mapper.readTree(body.path("input").asText());
            assertEquals(catalogue, input.at("/loadResults/1/data/context/dashboardWidgetTypes"));
            assertEquals("HYBRID", input.at("/loadResults/1/data/context/widgetCapabilityMatches/mode").asText());
        }
        assertEquals(2, calls.get());
        var assessment = mapper.readTree(transport.serialize(request, validator.projection("assessmentResponse"), "JSON_TEXT"));
        var assessmentInput = mapper.readTree(assessment.path("input").asText());
        assertFalse(assessmentInput.at("/loadResults/1/data/context").has("widgetCapabilityMatches"));
        assertEquals(2, calls.get());
        assertEquals(before, request);
    }

    @Test void explicitReviewSeedsTheWorkSubjectOfAnIndependentConversation() {
        var request = mapper.createObjectNode();
        request.putObject("dialogue").putObject("focus");
        var choice = request.putArray("resourceChoices").addObject().put("kind", "dashboard-review").put("label", "Accepted draft");
        choice.putObject("properties").put("reviewId", "accepted").put("explicitBase", true).put("requiredIntent", DashboardSkillContract.REFINE);
        var projection = new com.voltcanary.aiagent.adapter.PrimaryPlannerProjection(mapper, request,
                new ProtocolValidator(mapper).projection("plannerResponse"));
        assertEquals(DashboardSkillContract.REFINE, projection.request().at("/activeSubject/requiredIntent").asText());
    }
    @Test void editBoundaryKeepsExactCurrentTextAndAcceptedIdentityWithoutParsingProse() {
        var request = mapper.createObjectNode().put("kind", "step.request").put("messageId", "message-3")
                .put("message", "Use this new caption; leave the other choices unchanged.");
        request.putObject("context").putArray("goals").addObject().put("goalId", "retained");
        var base = request.putArray("loadResults").addObject().put("loaderId", "review.base").putObject("data").put("reviewId", "accepted");
        base.putArray("actions").addObject().put("tool", "CREATE_DASHBOARD").putObject("parameters").putObject("definition").put("name", "Existing name");
        PrimaryPromptProjection.changeContext(request);
        assertEquals("message-3", request.at("/changeRequest/sourceMessageId").asText());
        assertEquals(request.path("message"), request.at("/changeRequest/text"));
        assertEquals("Existing name", request.at("/changeRequest/acceptedName").asText());
        assertEquals("retained", request.at(request.at("/changeRequest/retainedGoalsRef").asText()).at("/0/goalId").asText());
    }
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void repeatedViewsHaveOneLosslessCatalogueWhileDistinctChoicesStayDistinct() throws Exception {
        var request = fixture();
        var choices = request.withObject("/loadResults/1/data/context").withArray("dashboardDataChoices");
        var shared = mapper.readTree("[{\"purpose\":\"CURRENT\",\"widgetType\":\"latest-value\",\"visualizations\":[\"number\"],\"readiness\":\"COMPATIBLE\"}]");
        ((ObjectNode) choices.get(0)).set("views", shared.deepCopy());
        choices.addObject().put("dataRef", "other-ref").put("valueType", "number").put("unit", "W").set("views", shared.deepCopy());
        choices.addObject().put("dataRef", "text-ref").put("valueType", "string").set("views", mapper.readTree("[{\"purpose\":\"HISTORY\",\"widgetType\":\"vehicle-timeline\",\"visualizations\":[\"list\"]}]"));
        var original = choices.deepCopy();
        PrimaryPromptProjection.workflowCatalogues(request);
        var context = request.at("/loadResults/1/data/context");
        assertEquals(1, context.path("dashboardViewSets").size());
        for (int index = 0; index < choices.size(); index++) {
            var expanded = ((ObjectNode) choices.get(index)).deepCopy();
            if (expanded.has("viewsRef")) {
                expanded.set("views", context.path("dashboardViewSets").path(expanded.remove("viewsRef").asText()));
            }
            assertEquals(original.get(index), expanded);
        }
        var once = request.deepCopy();
        PrimaryPromptProjection.workflowCatalogues(request);
        assertEquals(once, request);
    }

    @Test void compactReviewRetainsExactDefinitionDialogueFactsAndRoleGeometryIndex() throws Exception {
        var request = fixture();
        var before = request.deepCopy();
        PrimaryPromptProjection.dashboardBase(request);
        assertEquals(before.at("/loadResults/0/data/actions/0/parameters/definition"),
                request.at("/loadResults/0/data/actions/0/parameters/definition"));
        assertEquals(before.path("context"), request.path("context"));
        assertEquals(before.path("message"), request.path("message"));
        assertFalse(request.at("/loadResults/0/data/actions/0/parameters").has("prepareIntent"));
        assertFalse(request.at("/loadResults/0/data/actions/0/parameters").has("operationChanges"));
        var oldInventory = before.at("/loadResults/0/data/actions/0/currentWidgetInventory");
        var inventory = request.at("/loadResults/0/data/actions/0/currentWidgetInventory");
        for (String field : List.of("key", "role", "dataRef", "width", "height", "period", "breakpoints")) {
            assertEquals(oldInventory.at("/widgets/0/" + field), inventory.at("/widgets/0/" + field));
        }
        assertEquals(oldInventory.path("order"), inventory.path("order"));
        assertFalse(inventory.at("/widgets/0").has("binding"));
        assertFalse(request.at("/loadResults/1/data/context").has("previousDashboardIntent"));
        assertEquals(before.at("/loadResults/1/data/context/dashboardDraftTarget"), request.at("/loadResults/1/data/context/dashboardDraftTarget"));
        assertEquals(before.at("/loadResults/1/data/context/dashboardDataChoices"), request.at("/loadResults/1/data/context/dashboardDataChoices"));
        PrimaryPromptProjection.dashboardBase(request);
        var once = request.deepCopy();
        PrimaryPromptProjection.dashboardBase(request);
        assertEquals(once, request);
    }

    @Test void unrelatedReviewsAndUnmatchedContextAreNotDiscarded() throws Exception {
        var request = fixture();
        request.withObject("/loadResults/0/data/actions/0").put("tool", "CREATE_DEVICE");
        var before = request.deepCopy();
        PrimaryPromptProjection.dashboardBase(request);
        assertEquals(before, request);
        request.withObject("/loadResults/0/data/actions/0").put("tool", "UPDATE_DASHBOARD_DRAFT");
        request.withObject("/context").put("baseReviewId", "another-review");
        PrimaryPromptProjection.dashboardBase(request);
        assertTrue(request.at("/loadResults/1/data/context").has("previousDashboardIntent"));
    }

    @Test void allModesSendOnlyNecessaryContractsWithoutMutatingCanonicalRequest() throws Exception {
        var validator = new ProtocolValidator(mapper);
        var registry = new SkillRegistry(validator, PrimarySkillPackages.build(mapper, validator, List.of()));
        var config = new AiModelConfig();
        config.setModelId("pinned-fixture");
        var transport = new PrimaryModelTransport(mapper, new AiResolvedModel(null, config, "not-on-wire"), null, null);
        var request = fixture();
        request.putObject("instructions").put("text", "selected skill");
        var contract = request.putObject("contract").put("trustPolicy", "DUPLICATE_TRUST");
        contract.putObject("inputSchema").put("description", "INPUT_FIELDS");
        contract.putArray("parameterRules").add("QUESTION_POLICY");
        contract.putObject("resolvedInputs").put("deviceId", "confirmed-device");
        contract.putArray("bindings").add("confirmed-binding");
        var loader = contract.putArray("loaders").addObject().put("loaderId", "workflow.context").put("argumentsSchemaId", "workflow:args:1");
        loader.putObject("argumentsSchema").put("description", "AUTHORIZED_ARGUMENTS");
        loader.putObject("resultSchema").put("description", "REDUNDANT_RESULT_SCHEMA");
        var before = request.deepCopy();
        var schema = registry.responseProjection(DashboardSkillContract.REFINE, Set.of(DashboardSkillContract.REFINE), Set.of());
        for (String mode : List.of("JSON_TEXT", "STRICT_JSON", "NATIVE_TOOLS")) {
            var body = mapper.readTree(transport.serialize(request, schema, mode));
            var input = mapper.readTree(body.path("input").asText());
            assertEquals(before.path("context"), input.path("context"));
            assertEquals(before.at("/contract/resolvedInputs"), input.at("/contract/resolvedInputs"));
            assertEquals(before.at("/contract/bindings"), input.at("/contract/bindings"));
            assertFalse(body.toString().contains("REDUNDANT_RESULT_SCHEMA"));
            assertFalse(body.toString().contains("DUPLICATE_TRUST"));
            for (String required : List.of("INPUT_FIELDS", "QUESTION_POLICY", "AUTHORIZED_ARGUMENTS")) {
                assertTrue(body.path("instructions").asText().contains(required));
            }
            assertEquals(mode.equals("JSON_TEXT"), body.path("instructions").asText().contains("Response schema:"));
            assertEquals(before, request);
        }
    }

    private ObjectNode fixture() throws Exception {
        var request = (ObjectNode) mapper.readTree("""
                {"kind":"step.request","intentId":"REFINE_DASHBOARD_REVIEW","message":"Только название",
                 "context":{"baseReviewId":"accepted","facts":[{"value":false}],
                   "dialogue":{"messages":[{"text":"Исходная просьба"}],
                     "pendingQuestion":{"text":"Как назвать?","options":["Дом","Офис"]}}},
                 "loadResults":[{"loaderId":"review.base","data":{"reviewId":"accepted","version":7,"digest":"base-digest","actions":[
                   {"tool":"CREATE_DASHBOARD","parameters":{"prepareIntent":{"name":"Old","widgets":[{"key":"w","dataRef":"actual-ref","width":6,"height":3,"period":"LAST_6_HOURS"}]},
                     "operationChanges":["old-mask"],"expectedOutcomes":["old-goal"],
                     "definition":{"name":"Accepted","widgets":[{"id":"w","type":"latest-value","title":"Value","typeVersion":2,
                       "binding":{"deviceId":"device"},"query":{"metric":"measurement"},"presentation":{"decimals":2},"settings":{"showUnit":true},
                       "navigation":{"target":"details"},"payload":{"text":"Keep all fields"}}],
                       "layouts":{"lg":[{"widgetId":"w","x":0,"y":0,"w":6,"h":3}]}}}}]}},
                   {"loaderId":"workflow.context","data":{"context":{"previousDashboardIntent":{"name":"Old"},
                     "dashboardDraftTarget":{"etag":"fresh-etag","baseRevision":9},"dashboardDataChoices":[{"dataRef":"actual-ref","valueType":"number"}]}}}]}
                """);
        var action = request.withObject("/loadResults/0/data/actions/0");
        action.set("currentWidgetInventory", CurrentWidgetInventory.from(mapper, action.path("parameters")));
        return request;
    }
}
