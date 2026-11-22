package com.voltcanary.aiagent;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voltcanary.aiagent.adapter.PrimaryPlannerProjection;
import com.voltcanary.aiagent.orchestration.contract.ProtocolValidator;
import com.voltcanary.aiagent.orchestration.routing.ModelProposalRejected;
import com.voltcanary.aiagent.orchestration.routing.StrictOutputProjection;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlannerConditionalOutputTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ProtocolValidator validator = new ProtocolValidator(mapper);

    @Test
    void everyDecisionHasAValidDisjointWireVariant() throws Exception {
        for (String decision : List.of("PLAN", "AMEND", "CONTINUE", "ASK", "UNSUPPORTED")) {
            var value = response(decision);
            validator.validate("plannerResponse", value);
            var projection = new StrictOutputProjection(
                mapper,
                validator.projection("plannerResponse")
            );
            var wire = mapper.createObjectNode().set("proposal", value);
            validator.validateRegistered(projection.schema(), wire);
            assertEquals(value, projection.normalize(wire));
        }
    }

    @Test
    void contradictingDecisionFieldsAreRejectedByTheWireBeforeCanonicalValidation() throws Exception {
        var projection = new StrictOutputProjection(
            mapper,
            validator.projection("plannerResponse")
        );
        var plan = response("PLAN");
        plan.set("question", response("ASK").path("question"));
        rejects(projection, plan);
        plan = response("PLAN");
        plan.putArray("steps");
        rejects(projection, plan);
        var continued = response("CONTINUE");
        continued.putNull("targetStepId");
        rejects(projection, continued);
        var ask = response("ASK");
        ask.putNull("question");
        rejects(projection, ask);
        var initial = response("PLAN");
        initial.withArray("supersedesStepIds").add("old");
        rejects(projection, initial);
    }

    @Test
    void existingPlanCannotBeSerializedAsAnInitialPlanDecision() {
        var request = mapper.createObjectNode();
        request.putObject("planSummary").putArray("steps").addObject().put("stepId", "old");
        var projection = new PrimaryPlannerProjection(
            mapper,
            request,
            validator.projection("plannerResponse")
        );
        assertFalse(
            projection
                .schema()
                .at("/$defs/plannerResponse/properties/decision/enum")
                .toString()
                .contains("\"PLAN\"")
        );
        request.withObject("/planSummary").putArray("steps");
        var initial = new PrimaryPlannerProjection(
            mapper,
            request,
            validator.projection("plannerResponse")
        );
        assertFalse(
            initial
                .schema()
                .at("/$defs/plannerResponse/properties/decision/enum")
                .toString()
                .contains("AMEND")
        );
    }

    @Test
    void strictGoalSchemaPreservesFreshIdGuidanceAndServerRejectsOccupiedIds() throws Exception {
        var request = mapper.createObjectNode();
        request
            .putObject("planSummary")
            .putArray("goals")
            .addObject()
            .put("goalId", "goal")
            .put("disposition", "PENDING");
        var planner = new PrimaryPlannerProjection(
            mapper,
            request,
            validator.projection("plannerResponse")
        );
        var strict = new StrictOutputProjection(mapper, planner.schema());
        var id = strict.schema().at("/$defs/goalProposal/properties/goalId");
        assertTrue(id.path("description").asText().contains("existingGoalIds"));
        assertFalse(id.has("not"), "Provider schema uses the supported subset");

        var plan = response("PLAN");
        ((ObjectNode) plan.at("/proposedGoals/0")).remove("sourceMessageId");
        var wire = mapper.createObjectNode().set("proposal", plan);
        validator.validateRegistered(strict.schema(), wire);
        var rejected = assertThrows(ModelProposalRejected.class, () ->
            planner.normalize(strict.normalize(wire), validator)
        );
        assertTrue(rejected.diagnostic().contains("PLANNER_GOAL_ID_ALREADY_USED"));
    }

    @Test
    void selectedSubjectCannotBecomeAnEmptyTargetQuestionForDomainValues() throws Exception {
        var request = mapper
            .createObjectNode()
            .put("messageId", "00000000-0000-0000-0000-000000000001");
        request.putObject("dialogue").putObject("focus").put("reviewId", "accepted");
        request
            .putArray("resourceChoices")
            .addObject()
            .put("kind", "dashboard-review")
            .put("label", "Selected dashboard")
            .putObject("properties")
            .put("reviewId", "accepted")
            .put("requiredIntent", "REFINE_DASHBOARD_REVIEW");
        var projection = new PrimaryPlannerProjection(
            mapper,
            request,
            validator.projection("plannerResponse")
        );
        var ask = response("ASK");
        assertThrows(ModelProposalRejected.class, () -> projection.normalize(ask, validator));
        ((ObjectNode) ask.path("question")).putArray("missingFields").add("intent");
        projection.normalize(ask, validator);
    }

    @Test
    void springAiSdkKeepsTheDecisionConstraintsInTheActualNativeToolRequest() throws Exception {
        var captured =
            new java.util.concurrent.atomic.AtomicReference<com.fasterxml.jackson.databind.JsonNode>();
        var server = com.sun.net.httpserver.HttpServer.create(
            new java.net.InetSocketAddress("127.0.0.1", 0),
            0
        );
        var projection = new StrictOutputProjection(
            mapper,
            validator.projection("plannerResponse")
        );
        var wire = mapper.createObjectNode().set("proposal", response("PLAN"));
        server.createContext("/v1/chat/completions", exchange -> {
            captured.set(mapper.readTree(exchange.getRequestBody()));
            var tool = java.util.Map.of(
                "id",
                "call",
                "type",
                "function",
                "function",
                java.util.Map.of("name", "assistant_proposal", "arguments", wire.toString())
            );
            var completion = java.util.Map.of(
                "id",
                "fixture",
                "object",
                "chat.completion",
                "created",
                1,
                "model",
                "fixture",
                "choices",
                List.of(
                    java.util.Map.of(
                        "index",
                        0,
                        "finish_reason",
                        "tool_calls",
                        "message",
                        java.util.Map.of("role", "assistant", "tool_calls", List.of(tool))
                    )
                )
            );
            byte[] bytes = mapper.writeValueAsBytes(completion);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var provider = new com.voltcanary.aiagent.domain.AiModelProvider();
            provider.setProviderType(com.voltcanary.aiagent.domain.AiModelProviderType.OPENAI);
            provider.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            var config = new com.voltcanary.aiagent.domain.AiModelConfig();
            config.setModelId("fixture");
            var reply = new com.voltcanary.aiagent.adapter.springai.SpringAiExchange(mapper).call(
                new com.voltcanary.aiagent.llm.adapter.AiResolvedModel(provider, config, "fixture"),
                "Route",
                "request",
                projection.schema(),
                true,
                3000
            );
            assertNull(reply.failure());
            var actualSchema = captured.get().at("/tools/0/function/parameters");
            assertTrue(captured.get().at("/tools/0/function/strict").asBoolean());
            validator.validateRegistered(actualSchema, wire);
            var invalid = wire.deepCopy();
            ((ObjectNode) invalid.path("proposal")).putArray("steps");
            assertThrows(IllegalArgumentException.class, () ->
                validator.validateRegistered(actualSchema, invalid)
            );
            validator.validate(
                "plannerResponse",
                projection.normalize(mapper.readTree(reply.text()))
            );
        } finally {
            server.stop(0);
        }
    }

    private void rejects(StrictOutputProjection projection, ObjectNode value) {
        assertThrows(IllegalArgumentException.class, () ->
            validator.validateRegistered(
                projection.schema(),
                mapper.createObjectNode().set("proposal", value)
            )
        );
    }

    private ObjectNode response(String decision) throws Exception {
        var result = (ObjectNode) mapper.readTree(
            """
            {"version":"assistant-v2","kind":"planner.response","decision":"PLAN","explanation":"route",
             "targetStepId":null,"question":null,"supersedesStepIds":[],"supersedesGoalIds":[],"steps":[],"proposedGoals":[]}
            """
        );
        result.put("decision", decision);
        if (List.of("PLAN", "AMEND").contains(decision)) {
            result
                .withArray("steps")
                .addObject()
                .put("stepId", "next")
                .put("intentId", "CREATE_DASHBOARD")
                .putArray("dependsOn");
            var step = (ObjectNode) result.path("steps").get(0);
            step.putArray("inputs");
            step.putArray("goalIds").add("goal");
            result
                .withArray("proposedGoals")
                .addObject()
                .put("goalId", "goal")
                .put("text", "Prepare requested dashboard")
                .put("sourceMessageId", "00000000-0000-0000-0000-000000000001");
        }
        if (decision.equals("CONTINUE")) result.put("targetStepId", "existing");
        if (decision.equals("ASK")) {
            var question = result.putObject("question").put("text", "Which resource?");
            question.putArray("missingFields").add("target");
            question.putArray("choices");
        }
        return result;
    }
}
