package com.voltcanary.aiagent;

import static org.junit.jupiter.api.Assertions.*;

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

class PrimaryPromptProjectionTest {

    @Test
    void embeddingIndexAndQueryReplayFromTheDurableLedger() throws Exception {
        var fixture = new IntentScenarioSupport();
        var network = new java.util.concurrent.atomic.AtomicInteger();
        var embeddings = new com.voltcanary.aiagent.port.CapabilityEmbeddings() {
            public String identity() {
                return "durable-fixture";
            }

            public List<float[]> embed(List<String> texts) {
                assertEquals(
                    (long) network.incrementAndGet(),
                    fixture.jdbc.queryForObject(
                        "select sum(calls) from ai_model_budget_plans",
                        Long.class
                    )
                );
                return texts
                    .stream()
                    .map(text -> new float[] { 1, 1 })
                    .toList();
            }
        };
        var request = fixture();
        request.put("message", "show map");
        request
            .withObject("/loadResults/1/data/context")
            .putArray("dashboardWidgetTypes")
            .addObject()
            .put("type", "vehicle-map");
        var limits = new com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.Limits(
            2L,
            100_000L,
            1_000L,
            60_000L,
            200L,
            "USD"
        );
        var candidate =
            new com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.Candidate(
                "provider",
                "pinned",
                null,
                100_000L,
                90_000L,
                1_000L,
                "JSON_TEXT",
                null
            );
        var policy =
            new com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.RoutingPolicy(
                "assistant-v2",
                "routing.policy",
                "fixture",
                "1",
                "PINNED",
                "pinned",
                List.of(
                    new com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.RoutingPolicyProfilesItem(
                        "primary",
                        List.of(),
                        List.of("STEP"),
                        List.of(candidate),
                        false
                    )
                ),
                limits,
                limits,
                "RESERVED_UPPER_BOUND",
                100L,
                java.math.BigDecimal.ONE,
                null
            );
        var turn = new com.voltcanary.aiagent.orchestration.routing.AttemptLedger.Turn(
            fixture.session.owner(),
            fixture.conversation,
            java.util.UUID.randomUUID(),
            java.util.UUID.randomUUID()
        );
        var call = java.util.UUID.randomUUID();
        var config = new AiModelConfig();
        config.setModelId("fixture");
        var selected = new AiResolvedModel(null, config, "unused");
        var first = new PrimaryModelTransport(
            mapper,
            selected,
            null,
            null,
            new com.voltcanary.aiagent.service.WidgetCatalogueSearch(mapper, embeddings)
        );
        var ledger = fixture.ledger();
        ledger.open(turn, policy);
        var prepared = first.prepareContext(request.deepCopy(), ledger, turn, policy, call, "STEP");
        assertEquals(
            "HYBRID",
            prepared.at("/loadResults/1/data/context/widgetCapabilityMatches/mode").asText()
        );
        assertEquals(2, network.get());

        var restarted = new PrimaryModelTransport(
            mapper,
            selected,
            null,
            null,
            new com.voltcanary.aiagent.service.WidgetCatalogueSearch(mapper, embeddings)
        );
        assertEquals(
            prepared,
            restarted.prepareContext(
                request.deepCopy(),
                fixture.ledger(),
                turn,
                policy,
                call,
                "STEP"
            )
        );
        assertEquals(2, network.get());
        assertEquals(
            2L,
            fixture.jdbc.queryForObject(
                "select count(*) from ai_model_budget_attempts where status='SUCCEEDED' and usage_json is not null",
                Long.class
            )
        );
        assertEquals(0, ledger.remainingCost(turn));
    }

    @Test
    void dashboardWriterRetrievesOnceWithoutRemovingContractsOrLeakingHintsToAssessor() throws Exception {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var embeddings = new com.voltcanary.aiagent.port.CapabilityEmbeddings() {
            public String identity() {
                return "projection-test-space";
            }

            public List<float[]> embed(List<String> texts) {
                calls.incrementAndGet();
                return texts
                    .stream()
                    .map(text -> new float[] { 1, 1 })
                    .toList();
            }
        };
        var config = new AiModelConfig();
        config.setModelId("fixture");
        var transport = new PrimaryModelTransport(
            mapper,
            new AiResolvedModel(null, config, "unused"),
            null,
            null,
            new com.voltcanary.aiagent.service.WidgetCatalogueSearch(mapper, embeddings)
        );
        var request = fixture();
        request.put("message", "show notices");
        var catalogue = request
            .withObject("/loadResults/1/data/context")
            .putArray("dashboardWidgetTypes");
        var widget = catalogue
            .addObject()
            .put("type", "vehicle-map")
            .put("description", "Coordinates");
        widget
            .putObject("capabilities")
            .putArray("settingContracts")
            .addObject()
            .put("key", "showMapNotices")
            .put("label", "Show notices");
        var before = request.deepCopy();
        var validator = new ProtocolValidator(mapper);
        var ledger = org.mockito.Mockito.mock(
            com.voltcanary.aiagent.orchestration.routing.AttemptLedger.class
        );
        var turn = new com.voltcanary.aiagent.orchestration.routing.AttemptLedger.Turn(
            null,
            null,
            null,
            java.util.UUID.randomUUID()
        );
        org.mockito.Mockito.when(
            ledger.reserve(
                org.mockito.ArgumentMatchers.eq(turn),
                org.mockito.ArgumentMatchers.any()
            )
        ).thenReturn(
            new com.voltcanary.aiagent.orchestration.routing.AttemptLedger.Receipt(
                true,
                "STARTED",
                null
            )
        );
        var policy =
            new com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.RoutingPolicy(
                "assistant-v2",
                "routing.policy",
                "fixture",
                "1",
                "PINNED",
                null,
                List.of(),
                null,
                null,
                "RESERVED_UPPER_BOUND",
                100L,
                null,
                null
            );
        var unprepared = mapper.readTree(
            transport.serialize(request, validator.projection("stepResponse"), "JSON_TEXT")
        );
        assertFalse(
            mapper
                .readTree(unprepared.path("input").asText())
                .at("/loadResults/1/data/context")
                .has("widgetCapabilityMatches")
        );
        assertEquals(0, calls.get());
        var prepared = transport.prepareContext(
            request.deepCopy(),
            ledger,
            turn,
            policy,
            java.util.UUID.randomUUID(),
            "STEP"
        );
        for (int i = 0; i < 2; i++) {
            var body = mapper.readTree(
                transport.serialize(prepared, validator.projection("stepResponse"), "JSON_TEXT")
            );
            var input = mapper.readTree(body.path("input").asText());
            assertEquals(catalogue, input.at("/loadResults/1/data/context/dashboardWidgetTypes"));
            assertEquals(
                "HYBRID",
                input.at("/loadResults/1/data/context/widgetCapabilityMatches/mode").asText()
            );
        }
        assertEquals(2, calls.get());
        org.mockito.Mockito.verify(ledger, org.mockito.Mockito.times(2)).reserve(
            org.mockito.ArgumentMatchers.eq(turn),
            org.mockito.ArgumentMatchers.any()
        );
        var assessment = mapper.readTree(
            transport.serialize(prepared, validator.projection("assessmentResponse"), "JSON_TEXT")
        );
        var assessmentInput = mapper.readTree(assessment.path("input").asText());
        assertFalse(
            assessmentInput.at("/loadResults/1/data/context").has("widgetCapabilityMatches")
        );
        assertEquals(2, calls.get());
        assertEquals(before, request);

        var deniedTurn = new com.voltcanary.aiagent.orchestration.routing.AttemptLedger.Turn(
            null,
            null,
            null,
            java.util.UUID.randomUUID()
        );
        org.mockito.Mockito.when(
            ledger.reserve(
                org.mockito.ArgumentMatchers.eq(deniedTurn),
                org.mockito.ArgumentMatchers.any()
            )
        ).thenThrow(
            new com.voltcanary.aiagent.orchestration.routing.ModelRouting.Stop(
                "CALL_BUDGET_EXHAUSTED"
            )
        );
        var denied = transport.prepareContext(
            request.deepCopy(),
            ledger,
            deniedTurn,
            policy,
            java.util.UUID.randomUUID(),
            "STEP"
        );
        assertEquals(
            "LEXICAL_ONLY",
            denied.at("/loadResults/1/data/context/widgetCapabilityMatches/mode").asText()
        );
        assertEquals(2, calls.get());
    }

    @Test
    void plannerProjectsOnlyLiveCoveringStepsForPendingGoals() throws Exception {
        var request = mapper.readTree(
            """
            {"planSummary":{"goals":[{"goalId":"keep","disposition":"PENDING"},{"goalId":"done","disposition":"SATISFIED"}],
             "steps":[{"stepId":"live","status":"PREPARING","goalIds":["keep"]},
                      {"stepId":"old","status":"SUPERSEDED","goalIds":["keep"],"dependsOn":["live"]},
                      {"stepId":"cancelled","status":"CANCELLED","goalIds":["keep"],"dependsOn":["old"]}]}}
            """
        );
        var projected = new com.voltcanary.aiagent.adapter.PrimaryPlannerProjection(
            mapper,
            request,
            new ProtocolValidator(mapper).projection("plannerResponse")
        ).request();
        assertEquals(
            mapper.readTree("[{\"goalId\":\"keep\",\"coveringStepIds\":[\"live\"]}]"),
            projected.path("pendingGoalCoverage")
        );
        assertEquals(request.path("planSummary"), projected.path("planSummary"));
        assertEquals(
            mapper.readTree("[\"old\",\"cancelled\"]"),
            projected.at("/supersessionImpact/live")
        );
    }

    @Test
    void explicitReviewSeedsTheWorkSubjectOfAnIndependentConversation() {
        var request = mapper.createObjectNode();
        request.putObject("dialogue").putObject("focus");
        var choice = request
            .putArray("resourceChoices")
            .addObject()
            .put("kind", "dashboard-review")
            .put("label", "Accepted draft");
        choice
            .putObject("properties")
            .put("reviewId", "accepted")
            .put("explicitBase", true)
            .put("requiredIntent", DashboardSkillContract.REFINE);
        var projection = new com.voltcanary.aiagent.adapter.PrimaryPlannerProjection(
            mapper,
            request,
            new ProtocolValidator(mapper).projection("plannerResponse")
        );
        assertEquals(
            DashboardSkillContract.REFINE,
            projection.request().at("/activeSubject/requiredIntent").asText()
        );
    }

    @Test
    void editBoundaryKeepsExactCurrentTextAndAcceptedIdentityWithoutParsingProse() {
        var request = mapper
            .createObjectNode()
            .put("kind", "step.request")
            .put("messageId", "message-3")
            .put("message", "Use this new caption; leave the other choices unchanged.");
        var context = request.putObject("context");
        var goals = context.putArray("goals");
        goals
            .addObject()
            .put("goalId", "retained")
            .put("disposition", "PENDING")
            .put("sourceMessageId", "message-1");
        goals
            .addObject()
            .put("goalId", "same-source")
            .put("disposition", "PENDING")
            .put("sourceMessageId", "message-1");
        goals
            .addObject()
            .put("goalId", "new")
            .put("disposition", "PENDING")
            .put("sourceMessageId", "message-3");
        goals
            .addObject()
            .put("goalId", "other")
            .put("disposition", "PENDING")
            .put("sourceMessageId", "message-2");
        var messages = context.putObject("dialogue").putArray("messages");
        messages
            .addObject()
            .put("messageId", "message-1")
            .put("role", "USER")
            .put("text", "Keep the original chart");
        messages
            .addObject()
            .put("messageId", "message-2")
            .put("role", "ASSISTANT")
            .put("text", "Previous proposal");
        var loads = request.putArray("loadResults");
        var base = loads
            .addObject()
            .put("loaderId", "review.base")
            .putObject("data")
            .put("reviewId", "accepted");
        var action = base.putArray("actions").addObject().put("tool", "CREATE_DASHBOARD");
        action.putObject("parameters").putObject("definition").put("name", "Existing name");
        var widgets = action.putObject("currentWidgetInventory").putArray("widgets");
        action
            .withObject("/currentWidgetInventory")
            .putObject("order")
            .putArray("desktop")
            .add("speed-chart")
            .add("speed-card")
            .add("another-speed-card");
        widgets
            .addObject()
            .put("role", "CURRENT")
            .put("fieldKey", "speedKmh")
            .put("key", "speed-card");
        widgets
            .addObject()
            .put("role", "HISTORY")
            .put("fieldKey", "speedKmh")
            .put("key", "speed-chart");
        widgets
            .addObject()
            .put("role", "CURRENT")
            .put("fieldKey", "speedKmh")
            .put("key", "another-speed-card");
        loads
            .addObject()
            .put("loaderId", "workflow.context")
            .putObject("data")
            .putObject("context")
            .put("requestedDashboardName", "Requested name");
        PrimaryPromptProjection.changeContext(request);
        assertEquals("message-3", request.at("/changeRequest/sourceMessageId").asText());
        assertEquals(request.path("message"), request.at("/changeRequest/text"));
        assertEquals("Existing name", request.at("/changeRequest/acceptedName").asText());
        assertEquals("Requested name", request.at("/changeRequest/requestedName").asText());
        assertEquals(
            mapper.createArrayNode().add("speed-card").add("another-speed-card"),
            request.at("/changeRequest/widgetKeysByRoleAndField/CURRENT/speedKmh")
        );
        assertEquals(
            mapper.createArrayNode().add("speed-chart"),
            request.at("/changeRequest/widgetKeysByRoleAndField/HISTORY/speedKmh")
        );
        assertEquals(
            mapper.createArrayNode().add("speed-chart").add("speed-card").add("another-speed-card"),
            request.at(request.at("/changeRequest/widgetOrderRef").asText()).path("desktop")
        );
        assertEquals(
            "retained",
            request
                .at(request.at("/changeRequest/retainedGoalsRef").asText())
                .at("/0/goalId")
                .asText()
        );
        assertEquals(
            "Keep the original chart",
            request
                .at(request.at("/changeRequest/pendingGoalSources/retained").asText())
                .path("text")
                .asText()
        );
        assertEquals(
            "/changeRequest",
            request.at("/changeRequest/pendingGoalSources/new").asText()
        );
        assertFalse(request.at("/changeRequest/pendingGoalSources").has("other"));
        var retained = request.at("/changeRequest/retainedRequests");
        assertEquals(1, retained.size());
        assertEquals("message-1", retained.at("/0/sourceMessageId").asText());
        assertEquals("Keep the original chart", retained.at("/0/text").asText());
        assertEquals(
            mapper.createArrayNode().add("retained").add("same-source"),
            retained.at("/0/goalIds")
        );
    }

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void repeatedViewsHaveOneLosslessCatalogueWhileDistinctChoicesStayDistinct() throws Exception {
        var request = fixture();
        var choices = request
            .withObject("/loadResults/1/data/context")
            .withArray("dashboardDataChoices");
        var shared = mapper.readTree(
            "[{\"purpose\":\"CURRENT\",\"widgetType\":\"latest-value\",\"visualizations\":[\"number\"],\"readiness\":\"COMPATIBLE\"}]"
        );
        ((ObjectNode) choices.get(0)).set("views", shared.deepCopy());
        choices
            .addObject()
            .put("dataRef", "other-ref")
            .put("valueType", "number")
            .put("unit", "W")
            .set("views", shared.deepCopy());
        choices
            .addObject()
            .put("dataRef", "text-ref")
            .put("valueType", "string")
            .set(
                "views",
                mapper.readTree(
                    "[{\"purpose\":\"HISTORY\",\"widgetType\":\"vehicle-timeline\",\"visualizations\":[\"list\"]}]"
                )
            );
        var original = choices.deepCopy();
        PrimaryPromptProjection.workflowCatalogues(request);
        var context = request.at("/loadResults/1/data/context");
        assertEquals(1, context.path("dashboardViewSets").size());
        for (int index = 0; index < choices.size(); index++) {
            var expanded = ((ObjectNode) choices.get(index)).deepCopy();
            if (expanded.has("viewsRef")) {
                expanded.set(
                    "views",
                    context.path("dashboardViewSets").path(expanded.remove("viewsRef").asText())
                );
            }
            assertEquals(original.get(index), expanded);
        }
        var once = request.deepCopy();
        PrimaryPromptProjection.workflowCatalogues(request);
        assertEquals(once, request);
    }

    @Test
    void compactReviewRetainsExactDefinitionDialogueFactsAndRoleGeometryIndex() throws Exception {
        var request = fixture();
        var before = request.deepCopy();
        PrimaryPromptProjection.dashboardBase(request);
        assertEquals(
            before.at("/loadResults/0/data/actions/0/parameters/definition"),
            request.at("/loadResults/0/data/actions/0/parameters/definition")
        );
        assertEquals(before.path("context"), request.path("context"));
        assertEquals(before.path("message"), request.path("message"));
        assertFalse(request.at("/loadResults/0/data/actions/0/parameters").has("prepareIntent"));
        assertFalse(request.at("/loadResults/0/data/actions/0/parameters").has("operationChanges"));
        var oldInventory = before.at("/loadResults/0/data/actions/0/currentWidgetInventory");
        var inventory = request.at("/loadResults/0/data/actions/0/currentWidgetInventory");
        for (String field : List.of(
            "key",
            "role",
            "dataRef",
            "width",
            "height",
            "period",
            "breakpoints"
        )) {
            assertEquals(
                oldInventory.at("/widgets/0/" + field),
                inventory.at("/widgets/0/" + field)
            );
        }
        assertEquals(oldInventory.path("order"), inventory.path("order"));
        assertEquals("measurement", inventory.at("/widgets/0/fieldKey").asText());
        assertFalse(inventory.at("/widgets/0").has("binding"));
        assertFalse(request.at("/loadResults/1/data/context").has("previousDashboardIntent"));
        assertEquals(
            before.at("/loadResults/1/data/context/dashboardDraftTarget"),
            request.at("/loadResults/1/data/context/dashboardDraftTarget")
        );
        assertEquals(
            before.at("/loadResults/1/data/context/dashboardDataChoices"),
            request.at("/loadResults/1/data/context/dashboardDataChoices")
        );
        PrimaryPromptProjection.dashboardBase(request);
        var once = request.deepCopy();
        PrimaryPromptProjection.dashboardBase(request);
        assertEquals(once, request);
    }

    @Test
    void unrelatedReviewsAndUnmatchedContextAreNotDiscarded() throws Exception {
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

    @Test
    void allModesSendOnlyNecessaryContractsWithoutMutatingCanonicalRequest() throws Exception {
        var validator = new ProtocolValidator(mapper);
        var registry = new SkillRegistry(
            validator,
            PrimarySkillPackages.build(mapper, validator, List.of())
        );
        var config = new AiModelConfig();
        config.setModelId("pinned-fixture");
        var transport = new PrimaryModelTransport(
            mapper,
            new AiResolvedModel(null, config, "not-on-wire"),
            null,
            null
        );
        var request = fixture();
        request.putObject("instructions").put("text", "selected skill");
        var contract = request.putObject("contract").put("trustPolicy", "DUPLICATE_TRUST");
        contract.putObject("inputSchema").put("description", "INPUT_FIELDS");
        contract.putArray("parameterRules").add("QUESTION_POLICY");
        contract.putObject("resolvedInputs").put("deviceId", "confirmed-device");
        contract.putArray("bindings").add("confirmed-binding");
        var loader = contract
            .putArray("loaders")
            .addObject()
            .put("loaderId", "workflow.context")
            .put("argumentsSchemaId", "workflow:args:1");
        loader.putObject("argumentsSchema").put("description", "AUTHORIZED_ARGUMENTS");
        loader.putObject("resultSchema").put("description", "REDUNDANT_RESULT_SCHEMA");
        String requestedName = "Новое точное название";
        var workflow = request.withObject("/loadResults/1/data");
        workflow.withObject("/context").put("requestedDashboardName", requestedName);
        workflow.set(
            "operationSchema",
            DashboardSkillContract.schema(
                mapper,
                DashboardSkillContract.REFINE,
                java.util.Map.of("requestedDashboardName", requestedName)
            )
        );
        var before = request.deepCopy();
        var schema = registry.responseProjection(
            DashboardSkillContract.REFINE,
            Set.of(DashboardSkillContract.REFINE),
            Set.of()
        );
        for (String mode : List.of("JSON_TEXT", "STRICT_JSON", "NATIVE_TOOLS")) {
            var body = mapper.readTree(transport.serialize(request, schema, mode));
            var input = mapper.readTree(body.path("input").asText());
            assertEquals(before.path("context"), input.path("context"));
            assertEquals(
                before.at("/contract/resolvedInputs"),
                input.at("/contract/resolvedInputs")
            );
            assertEquals(before.at("/contract/bindings"), input.at("/contract/bindings"));
            assertFalse(body.toString().contains("REDUNDANT_RESULT_SCHEMA"));
            assertFalse(body.toString().contains("DUPLICATE_TRUST"));
            for (String required : List.of(
                "INPUT_FIELDS",
                "QUESTION_POLICY",
                "AUTHORIZED_ARGUMENTS"
            )) {
                assertTrue(body.path("instructions").asText().contains(required));
            }
            assertEquals(
                mode.equals("JSON_TEXT"),
                body.path("instructions").asText().contains("Response schema:")
            );
            assertEquals(requestedName, input.at("/changeRequest/requestedName").asText());
            var wire = switch (mode) {
                case "JSON_TEXT" -> mapper.readTree(
                    body
                        .path("instructions")
                        .asText()
                        .split("Response schema:\\R", 2)[1]
                        .lines()
                        .findFirst()
                        .orElseThrow()
                );
                case "STRICT_JSON" -> body.at("/text/format/schema");
                default -> body.at("/tools/0/parameters");
            };
            var variants = wire.at(
                "/$defs/command/properties/payload/properties/operations/items/anyOf"
            );
            var rename = java.util.stream.StreamSupport.stream(variants.spliterator(), false)
                .filter(variant ->
                    "rename_dashboard".equals(variant.at("/properties/op/enum/0").asText())
                )
                .findFirst()
                .orElseThrow();
            assertEquals(
                mapper.createArrayNode().add(requestedName),
                rename.at("/properties/name/enum")
            );
            assertEquals(before, request);
        }
    }

    private ObjectNode fixture() throws Exception {
        var request = (ObjectNode) mapper.readTree(
            """
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
            """
        );
        var action = request.withObject("/loadResults/0/data/actions/0");
        action.set(
            "currentWidgetInventory",
            CurrentWidgetInventory.from(mapper, action.path("parameters"))
        );
        return request;
    }
}
