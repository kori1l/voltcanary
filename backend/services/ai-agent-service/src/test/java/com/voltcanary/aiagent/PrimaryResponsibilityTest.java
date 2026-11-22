package com.voltcanary.aiagent;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.aiagent.adapter.PrimaryModelTransport;
import com.voltcanary.aiagent.domain.AiModelConfig;
import com.voltcanary.aiagent.llm.adapter.AiResolvedModel;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol;
import com.voltcanary.aiagent.orchestration.contract.ProtocolValidator;
import com.voltcanary.aiagent.orchestration.contract.SkillRegistry;
import com.voltcanary.aiagent.service.DashboardSkillContract;
import com.voltcanary.aiagent.service.PrimarySkillPackages;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PrimaryResponsibilityTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ProtocolValidator validator = new ProtocolValidator(mapper);
    private final Set<String> intents = Set.of(
        "CREATE_DASHBOARD",
        "REFINE_DASHBOARD_REVIEW",
        "UPDATE_DASHBOARD_DRAFT"
    );
    private final SkillRegistry registry = new SkillRegistry(
        validator,
        PrimarySkillPackages.build(mapper, validator, List.of())
    );

    @Test
    void catalogueAdvertisesEditCapabilitiesWithoutAllowingPlannerDomainParameters() {
        for (var entry : registry.catalogue(intents, Set.of())) {
            assertTrue(
                entry.description().contains("rename_dashboard"),
                "Renaming is supported despite name being absent from planner inputs"
            );
            assertTrue(entry.description().contains("place_widgets"));
            assertTrue(entry.description().contains("set_presentation"));
            assertFalse(entry.inputKeys().contains("name"));
            assertFalse(entry.inputKeys().contains("widgetKeys"));
            assertEquals(
                !entry.intentId().equals(DashboardSkillContract.CREATE),
                entry.description().contains("restore_review")
            );
        }
    }

    @Test
    void catalogueMeaningChangesInvalidateThePinnedRegistryDigest() {
        var packages = new java.util.ArrayList<>(
            PrimarySkillPackages.build(mapper, validator, List.of())
        );
        var original = packages.getFirst();
        var manifest = original.manifest().deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) manifest).put(
            "description",
            "Changed workflow capabilities"
        );
        packages.set(
            0,
            new SkillRegistry.Package(
                manifest,
                original.schemas(),
                original.requires(),
                original.loaderSchemas(),
                original.plannerFields()
            )
        );
        assertNotEquals(
            registry.versionDigest(),
            new SkillRegistry(validator, packages).versionDigest()
        );
    }

    @Test
    void acceptedDashboardContinuationAndDomainQuestionsAreRejectedBeforeAnotherStepCall() throws Exception {
        var f = new IntentScenarioSupport();
        var runtime = f.orchestration(
            new com.voltcanary.aiagent.orchestration.plan.OrchestrationPorts.Models() {
                public AssistantProtocol.PlannerResponse plan(
                    com.voltcanary.aiagent.orchestration.plan.IntentPlans.ModelInput input,
                    com.voltcanary.aiagent.orchestration.plan.OrchestrationPorts.Session session,
                    java.util.UUID call
                ) {
                    return f.plan(
                        input,
                        List.of(
                            new AssistantProtocol.StepProposal(
                                "d1",
                                "CREATE_DASHBOARD",
                                List.of(),
                                List.of(),
                                List.of("g")
                            )
                        )
                    );
                }

                public AssistantProtocol.StepResponse step(
                    com.voltcanary.aiagent.orchestration.plan.IntentPlans.ModelInput input,
                    com.voltcanary.aiagent.orchestration.plan.OrchestrationPorts.Session session,
                    java.util.UUID call
                ) {
                    return f.prepared(
                        "CREATE_DASHBOARD",
                        mapper
                            .createObjectNode()
                            .put("name", "Accepted")
                            .put("deviceId", java.util.UUID.randomUUID().toString())
                    );
                }
            }
        );
        var state = f.send(runtime, null, "Create a dashboard");
        var input = new com.voltcanary.aiagent.orchestration.plan.IntentPlans.ModelInput(
            null,
            state,
            java.util.Map.of(),
            new com.voltcanary.aiagent.orchestration.plan.IntentPlans.SourceMessage(
                java.util.UUID.randomUUID(),
                "Give it the name Workshop"
            )
        );
        var continued = mapper.valueToTree(f.continued("d1"));
        var failure = assertThrows(
            com.voltcanary.aiagent.orchestration.routing.ModelProposalRejected.class,
            () -> com.voltcanary.aiagent.service.PrimaryPlannerChecks.validate(input, continued)
        );
        assertTrue(failure.diagnostic().contains("PLANNER_ACCEPTED_DASHBOARD_REQUIRES_AMEND"));
        var question = mapper.createObjectNode().put("decision", "ASK");
        question.putObject("question").putArray("missingFields").add("tramName");
        assertTrue(
            assertThrows(
                com.voltcanary.aiagent.orchestration.routing.ModelProposalRejected.class,
                () -> com.voltcanary.aiagent.service.PrimaryPlannerChecks.validate(input, question)
            )
                .diagnostic()
                .contains("PLANNER_QUESTION_OUTSIDE_ROUTING_SCOPE")
        );
        question.withObject("/question").putArray("missingFields").add("intent");
        assertDoesNotThrow(() ->
            com.voltcanary.aiagent.service.PrimaryPlannerChecks.validate(input, question)
        );
        var amended = mapper.createObjectNode().put("decision", "AMEND");
        assertDoesNotThrow(() ->
            com.voltcanary.aiagent.service.PrimaryPlannerChecks.validate(input, amended)
        );
        amended.putArray("proposedGoals").addObject().put("goalId", "new-goal");
        var proposed = amended.putArray("steps").addObject().put("stepId", "replacement");
        proposed.putArray("goalIds").add("unknown-goal");
        var original = amended.deepCopy();
        var missing = assertThrows(
            com.voltcanary.aiagent.orchestration.routing.ModelProposalRejected.class,
            () -> com.voltcanary.aiagent.service.PrimaryPlannerChecks.validate(input, amended)
        );
        assertTrue(missing.diagnostic().contains("replacement"));
        assertTrue(missing.diagnostic().contains("unknown-goal"));
        assertTrue(missing.diagnostic().contains("new-goal"));
        assertEquals(original, amended);
        proposed.putArray("goalIds").add(state.goals().getFirst().goalId()).add("new-goal");
        assertDoesNotThrow(() ->
            com.voltcanary.aiagent.service.PrimaryPlannerChecks.validate(input, amended)
        );
        amended.putArray("supersedesGoalIds").add(state.goals().getFirst().goalId());
        assertThrows(com.voltcanary.aiagent.orchestration.routing.ModelProposalRejected.class, () ->
            com.voltcanary.aiagent.service.PrimaryPlannerChecks.validate(input, amended)
        );
        assertEquals(
            "READY",
            f.reviews.find(state.reviews().get("d1").reviewId(), f.session.owner()).status()
        );
        assertTrue(f.mutations.isEmpty());
    }

    @Test
    void assessorWireSeesOriginalRequestAndCandidateWithoutWriterGoalsOrMasks() throws Exception {
        var config = new AiModelConfig();
        config.setModelId("pinned-fixture");
        var transport = new PrimaryModelTransport(
            mapper,
            new AiResolvedModel(null, config, "secret"),
            null,
            null
        );
        var request = mapper
            .createObjectNode()
            .put("kind", "step.request")
            .put("intentId", DashboardSkillContract.REFINE)
            .put("message", "Exact user words");
        request.put("messageId", "current-source");
        request.putObject("instructions").put("text", "WRITER_INSTRUCTIONS");
        request
            .putObject("context")
            .putArray("goals")
            .addObject()
            .put("text", "WRITER_GOAL")
            .put("disposition", "PENDING")
            .put("sourceMessageId", "prior-source");
        request
            .withObject("/context")
            .putObject("dialogue")
            .putArray("messages")
            .addObject()
            .put("role", "USER")
            .put("text", "Original request");
        var review = request
            .putArray("loadResults")
            .addObject()
            .put("loaderId", "review.base")
            .putObject("data");
        var parameters = review.putArray("actions").addObject().putObject("parameters");
        parameters.put("operationChanges", "WRITER_MASK");
        parameters.putObject("definition").put("name", "Actual base");
        request
            .withArray("loadResults")
            .addObject()
            .put("loaderId", "verification.candidate")
            .putObject("data")
            .putObject("definition")
            .put("name", "Actual candidate");
        for (String mode : List.of("JSON_TEXT", "STRICT_JSON", "NATIVE_TOOLS")) {
            var body = mapper.readTree(
                transport.serialize(request, validator.projection("assessmentResponse"), mode)
            );
            assertTrue(body.path("instructions").asText().contains("dashboard-assessment-v1"));
            assertFalse(body.toString().contains("WRITER_"));
            assertTrue(body.toString().contains("Original request"));
            assertTrue(body.toString().contains("Actual candidate"));
            assertEquals(
                mapper.createArrayNode().add("current-source").add("prior-source"),
                mapper.readTree(body.path("input").asText()).path("requiredSourceMessageIds")
            );
            assertTrue(request.at("/context/goals").size() > 0);
        }
    }

    @Test
    void plannerAcceptsResourceBindingsButRejectsDomainParameters() throws Exception {
        var f = new IntentScenarioSupport();
        var runtime = f.orchestration(
            new com.voltcanary.aiagent.orchestration.plan.OrchestrationPorts.Models() {
                public AssistantProtocol.PlannerResponse plan(
                    com.voltcanary.aiagent.orchestration.plan.IntentPlans.ModelInput input,
                    com.voltcanary.aiagent.orchestration.plan.OrchestrationPorts.Session session,
                    java.util.UUID call
                ) {
                    var response = f.plan(
                        input,
                        List.of(
                            new AssistantProtocol.StepProposal(
                                "dashboard",
                                "CREATE_DASHBOARD",
                                List.of(),
                                List.of(),
                                List.of("g")
                            )
                        )
                    );
                    var json = mapper.valueToTree(response);
                    registry.validatePlanner(json, intents, Set.of());
                    for (String field : List.of("name", "period", "widgetKeys")) {
                        var invalid = json.deepCopy();
                        ((com.fasterxml.jackson.databind.node.ObjectNode) invalid.at("/steps/0"))
                            .putArray("inputs")
                            .addObject()
                            .put("field", field)
                            .putObject("binding")
                            .put("kind", "literal")
                            .put("value", "invented");
                        assertEquals(
                            "PLANNER_PARAMETER_NOT_ALLOWED",
                            assertThrows(IllegalArgumentException.class, () ->
                                registry.validatePlanner(invalid, intents, Set.of())
                            ).getMessage()
                        );
                    }
                    return response;
                }

                public AssistantProtocol.StepResponse step(
                    com.voltcanary.aiagent.orchestration.plan.IntentPlans.ModelInput input,
                    com.voltcanary.aiagent.orchestration.plan.OrchestrationPorts.Session session,
                    java.util.UUID call
                ) {
                    return f.prepared(
                        "CREATE_DASHBOARD",
                        mapper
                            .createObjectNode()
                            .put("name", "Fixture")
                            .put("deviceId", java.util.UUID.randomUUID().toString())
                    );
                }
            }
        );
        f.send(runtime, null, "Создай дашборд");
        for (var entry : registry.catalogue(intents, Set.of())) {
            assertEquals(
                Set.of("deviceId", "deviceIds", "sourceId"),
                Set.copyOf(entry.inputKeys())
            );
            assertEquals(
                Set.of("workflow.context", "conversation.history"),
                Set.copyOf(registry.manifest(entry.intentId(), intents, Set.of()).loaderIds())
            );
        }
    }

    @Test
    void transportUsesOneDynamicSchemaKeepsDialogueBindingsAndReviewAndDoesNotMutateCanonicalRequest() throws Exception {
        var config = new AiModelConfig();
        config.setModelId("pinned-fixture");
        var transport = new PrimaryModelTransport(
            mapper,
            new AiResolvedModel(null, config, "secret"),
            null,
            null
        );
        var request = mapper
            .createObjectNode()
            .put("kind", "step.request")
            .put("intentId", DashboardSkillContract.REFINE);
        request.putObject("instructions").put("text", "focused review skill");
        var contract = request.putObject("contract");
        contract.set(
            "commandSchema",
            DashboardSkillContract.schema(mapper, DashboardSkillContract.REFINE)
        );
        contract.putArray("bindings").add("confirmed binding");
        contract.putObject("resolvedInputs").put("deviceId", "confirmed resource");
        request
            .putObject("context")
            .put("message", "Текущие карточки наверх")
            .put("history", "Предыдущий точный текст")
            .put("pendingQuestion", "Какие карточки?")
            .put("review.base", "accepted definition");
        var dynamic = DashboardSkillContract.schema(
            mapper,
            DashboardSkillContract.REFINE,
            java.util.Map.of("dashboardDataChoices", List.of())
        );
        var loaded = request
            .putArray("loadResults")
            .addObject()
            .put("loaderId", "workflow.context")
            .putObject("data");
        loaded.set("operationSchema", dynamic);
        loaded.putObject("context").put("name", "Подтверждено");
        var before = request.deepCopy();
        for (String mode : List.of("JSON_TEXT", "STRICT_JSON", "NATIVE_TOOLS")) {
            var body = mapper.readTree(
                transport.serialize(
                    request,
                    registry.responseProjection(DashboardSkillContract.REFINE, intents, Set.of()),
                    mode
                )
            );
            var input = mapper.readTree(body.path("input").asText());
            assertEquals(request.path("context"), input.path("context"));
            assertEquals(request.at("/contract/bindings"), input.at("/contract/bindings"));
            assertEquals(
                request.at("/contract/resolvedInputs"),
                input.at("/contract/resolvedInputs")
            );
            assertFalse(input.at("/loadResults/0/data").has("operationSchema"));
            assertFalse(input.path("contract").has("commandSchema"));
            assertEquals(
                mode.equals("JSON_TEXT"),
                body.path("instructions").asText().contains(dynamic.toString())
            );
            assertEquals(before, request);
        }
    }

    @Test
    void amendmentRetainsAcceptedReviewButRejectsDependingOnItsSupersededStep() throws Exception {
        var f = new IntentScenarioSupport();
        var runtime = f.orchestration(
            new com.voltcanary.aiagent.orchestration.plan.OrchestrationPorts.Models() {
                public AssistantProtocol.PlannerResponse plan(
                    com.voltcanary.aiagent.orchestration.plan.IntentPlans.ModelInput input,
                    com.voltcanary.aiagent.orchestration.plan.OrchestrationPorts.Session session,
                    java.util.UUID call
                ) {
                    return f.plan(
                        input,
                        List.of(
                            new AssistantProtocol.StepProposal(
                                "dashboard",
                                "CREATE_DASHBOARD",
                                List.of(),
                                List.of(),
                                List.of("g")
                            )
                        )
                    );
                }

                public AssistantProtocol.StepResponse step(
                    com.voltcanary.aiagent.orchestration.plan.IntentPlans.ModelInput input,
                    com.voltcanary.aiagent.orchestration.plan.OrchestrationPorts.Session session,
                    java.util.UUID call
                ) {
                    return f.prepared(
                        "CREATE_DASHBOARD",
                        mapper
                            .createObjectNode()
                            .put("name", "Accepted")
                            .put("deviceId", java.util.UUID.randomUUID().toString())
                    );
                }
            }
        );
        var state = f.send(runtime, null, "Создай дашборд");
        var oldReview = state.focus().reviewId();
        assertNotNull(oldReview);
        var sources = f.store.sourceMessages(f.session.owner(), f.conversation);
        var source = sources.iterator().next();
        var dropsOriginalGoal = new AssistantProtocol.PlannerResponse(
            "assistant-v2",
            "planner.response",
            "AMEND",
            "Replace pending dashboard",
            null,
            List.of("dashboard"),
            null,
            List.of(
                new AssistantProtocol.StepProposal(
                    "replacement",
                    "CREATE_DASHBOARD",
                    List.of(),
                    List.of(),
                    List.of("rename")
                )
            ),
            List.of(new AssistantProtocol.GoalProposal("rename", source, "Rename dashboard")),
            List.of()
        );
        assertEquals(
            "UNCOVERED_RETAINED_GOAL",
            assertThrows(com.voltcanary.aiagent.orchestration.plan.IntentPlans.Rejected.class, () ->
                f.graph.apply(
                    state,
                    dropsOriginalGoal,
                    sources,
                    source,
                    true,
                    f.skills.authorized(),
                    Set.of()
                )
            ).getMessage()
        );
        for (var dependencies : List.of(List.of("dashboard"), List.<String>of())) {
            var decision = new AssistantProtocol.PlannerResponse(
                "assistant-v2",
                "planner.response",
                "AMEND",
                "Replace pending dashboard",
                null,
                List.of("dashboard"),
                null,
                List.of(
                    new AssistantProtocol.StepProposal(
                        "replacement",
                        "CREATE_DASHBOARD",
                        dependencies,
                        List.of(),
                        List.of("g")
                    )
                ),
                List.of(),
                List.of()
            );
            if (!dependencies.isEmpty()) {
                assertEquals(
                    "DEPENDENCY_UNAVAILABLE",
                    assertThrows(
                        com.voltcanary.aiagent.orchestration.plan.IntentPlans.Rejected.class,
                        () ->
                            f.graph.apply(
                                state,
                                decision,
                                sources,
                                sources.iterator().next(),
                                true,
                                f.skills.authorized(),
                                Set.of()
                            )
                    ).getMessage()
                );
            } else {
                var revised = f.graph.apply(
                    state,
                    decision,
                    sources,
                    sources.iterator().next(),
                    true,
                    f.skills.authorized(),
                    Set.of()
                );
                assertEquals(oldReview, revised.focus().reviewId());
                assertNull(revised.focus().stepId());
                assertEquals("SUPERSEDED", revised.step("dashboard").status());
                assertEquals("READY", revised.step("replacement").status());
                assertTrue(revised.reviews().isEmpty());
                assertFalse(state.reviews().isEmpty());
            }
        }
        assertTrue(f.mutations.isEmpty());
    }
}
