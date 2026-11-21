package com.voltcanary.aiagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voltcanary.aiagent.adapter.PrimaryModelTransport;
import com.voltcanary.aiagent.adapter.PrimaryPlannerProjection;
import com.voltcanary.aiagent.domain.AiModelConfig;
import com.voltcanary.aiagent.llm.adapter.AiResolvedModel;
import com.voltcanary.aiagent.orchestration.contract.ProtocolValidator;
import com.voltcanary.aiagent.orchestration.routing.ModelProposalRejected;
import com.voltcanary.aiagent.service.PrimaryPlannerGuide;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol;
import com.voltcanary.aiagent.orchestration.contract.SkillRegistry;
import com.voltcanary.aiagent.orchestration.routing.AttemptLedger;
import com.voltcanary.aiagent.orchestration.routing.BudgetedModelCalls;
import com.voltcanary.aiagent.orchestration.routing.ModelRouting;
import com.voltcanary.aiagent.port.AssistantTraceRecorder;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class PrimaryPlannerProjectionTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ProtocolValidator validator = new ProtocolValidator(mapper);
    private static final String DEVICE = "231e4e65-b376-4d1b-90c1-e14cd566742b";
    private static final String MESSAGE = "ebf0078a-49f3-41c6-9bd5-fb9c35f0d779";

    @ParameterizedTest
    @ValueSource(strings = {"JSON_TEXT", "STRICT_JSON", "NATIVE_TOOLS"})
    void springAiExchangeReservesBeforeNetworkAndReplaysCanonicalOutputFromDurableLedger(String mode) throws Exception {
        var f = new IntentScenarioSupport();
        var network = new AtomicInteger();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            int invocation = network.incrementAndGet();
            assertEquals((long) invocation, f.jdbc.queryForObject("select sum(calls) from ai_model_budget_plans", Long.class));
            var requestBody = mapper.readTree(exchange.getRequestBody());
            String tool = requestBody.at("/tools/0/function/name").asText();
            entered.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) throw new java.io.IOException("Test response gate timed out");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new java.io.IOException(interrupted);
            }
            var reply = mapper.createObjectNode().put("id", "fixture").put("object", "chat.completion").put("created", 1).put("model", "pinned-fixture");
            try {
                var choice = reply.putArray("choices").addObject().put("index", 0)
                        .put("finish_reason", mode.equals("NATIVE_TOOLS") ? "tool_calls" : "stop");
                var message = choice.putObject("message").put("role", "assistant");
                String proposal = mode.equals("JSON_TEXT") ? response().toString()
                        : mapper.createObjectNode().set("proposal", response()).toString();
                if (invocation == 2) proposal = "{broken json";
                if (mode.equals("NATIVE_TOOLS")) {
                    message.putArray("tool_calls").addObject().put("id", "fixture-tool").put("type", "function")
                            .putObject("function").put("name", tool).put("arguments", proposal);
                } else {
                    message.put("content", proposal);
                }
            } catch (Exception invalidFixture) { throw new java.io.IOException(invalidFixture); }
            byte[] bytes = mapper.writeValueAsBytes(reply);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var provider = new com.voltcanary.aiagent.domain.AiModelProvider();
            provider.setProviderType(com.voltcanary.aiagent.domain.AiModelProviderType.OPENAI);
            provider.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            var config = new AiModelConfig();
            config.setModelId("pinned-fixture");
            config.setMaxOutputTokens(1000);
            config.setTimeoutMs(20_000);
            var runs = org.mockito.Mockito.mock(com.voltcanary.aiagent.llm.adapter.AiAgentModelRunRecorder.class);
            org.mockito.Mockito.doThrow(new IllegalStateException("fixture diagnostic outage")).when(runs).record(
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
            var transport = new PrimaryModelTransport(mapper, new AiResolvedModel(provider, config, "fixture"), f.session.user(), runs);
            String body = transport.serialize(request(), validator.projection("plannerResponse"), mode);
            var candidate = new AssistantProtocol.Candidate("provider", "pinned", null, 1_000_000L, 900_000L, 1000L, mode, null);
            var route = new ModelRouting.Route(candidate, ModelRouting.estimate(body, 1000, 4096), 500L, null, "PINNED_EXPLICIT");
            var limits = new AssistantProtocol.Limits(2L, 4_000_000L, 2000L, 60_000L, 1000L, "USD");
            var policy = new AssistantProtocol.RoutingPolicy("assistant-v2", "routing.policy", "policy", "1", "PINNED", "pinned",
                    List.of(new AssistantProtocol.RoutingPolicyProfilesItem("primary", List.of(), List.of("PLANNER"), List.of(candidate), false)),
                    limits, limits, "RESERVED_UPPER_BOUND", 500L, BigDecimal.ONE, null);
            var turn = new AttemptLedger.Turn(f.session.owner(), f.conversation, UUID.randomUUID(), UUID.randomUUID());
            var ledger = f.ledger();
            ledger.open(turn, policy);
            var attempt = new AttemptLedger.Attempt(UUID.randomUUID(), "PLANNER", null, SkillRegistry.digest(body), "policy", "1", "catalogue-v1", "assistant-v2", "dialogue-v1", route, "PRIMARY");
            var correlation = new AssistantTraceRecorder.Correlation(turn.owner(), turn.conversationId(), UUID.randomUUID(), turn.turnId(), turn.turnId());
            JsonNode result;
            try (var worker = Executors.newSingleThreadExecutor()) {
                var first = worker.submit(() -> new BudgetedModelCalls(ledger, validator, f.traces)
                        .call(turn, attempt, body, "plannerResponse", correlation, transport, proposal -> { }));
                try {
                    assertTrue(entered.await(10, TimeUnit.SECONDS));
                    var duplicate = assertThrows(ModelRouting.Stop.class, () -> new BudgetedModelCalls(f.ledger(), validator, f.traces)
                            .call(turn, attempt, body, "plannerResponse", correlation, transport, proposal -> { }));
                    assertEquals("CALL_ALREADY_RESERVED:STARTED", duplicate.getMessage());
                    assertEquals(1, network.get());
                } finally {
                    release.countDown();
                }
                result = first.get(10, TimeUnit.SECONDS);
            }
            assertEquals(DEVICE, result.at("/steps/0/inputs/0/binding/value").asText());
            assertTrue(f.traceEvents.contains("MODEL_CALL:STARTED"));
            var replay = new BudgetedModelCalls(f.ledger(), validator, f.traces).call(turn, attempt, body,
                    "plannerResponse", correlation, (wire, replayRoute, timeout) -> {
                        throw new AssertionError("Completed replay must not enter the transport");
                    }, proposal -> { });
            assertEquals(result, replay);
            assertEquals(1, network.get());

            var malformed = new AttemptLedger.Attempt(UUID.randomUUID(), "PLANNER", null, SkillRegistry.digest(body),
                    "policy", "1", "catalogue-v1", "assistant-v2", "dialogue-v1", route, "PRIMARY");
            assertThrows(RuntimeException.class, () -> new BudgetedModelCalls(ledger, validator, f.traces)
                    .call(turn, malformed, body, "plannerResponse", correlation, transport, proposal -> { }));
            assertEquals(2, network.get(), "Malformed output must not cause a hidden SDK exchange");
            assertEquals("FAILED", f.jdbc.queryForObject(
                    "select status from ai_model_budget_attempts where plan_id=? and call_id=?",
                    String.class, turn.planId(), malformed.callId()));
        } finally { server.stop(0); }
    }

    @Test void activeSubjectDoesNotRewriteAnExplicitNewWorkflowOrTheCurrentUserWords() throws Exception {
        var config = new AiModelConfig();
        config.setModelId("pinned-fixture");
        var transport = new PrimaryModelTransport(mapper, new AiResolvedModel(null, config, "not-on-wire"), null, null);
        var request = request().put("message", "Создай ещё один новый дашборд; прежний вариант сохрани.");
        var review = request.withArray("resourceChoices").addObject().put("ref", "review").put("kind", "dashboard-review").put("label", "Accepted");
        review.putObject("properties").put("reviewId", "review").put("requiredIntent", "REFINE_DASHBOARD_REVIEW");
        request.withObject("/dialogue").putObject("focus").put("reviewId", "review");
        for (String mode : List.of("JSON_TEXT", "STRICT_JSON", "NATIVE_TOOLS")) {
            String body = transport.serialize(request, validator.projection("plannerResponse"), mode);
            var wireRequest = mapper.readTree(mapper.readTree(body).path("input").asText());
            assertEquals(request.path("message"), wireRequest.path("message"));
            assertEquals(request.path("dialogue"), wireRequest.path("dialogue"));
            assertEquals("resource2", wireRequest.at("/activeSubject/ref").asText());
            var selected = response().put("decision", "AMEND");
            var canonical = transport.normalize(selected, body);
            validator.validate("plannerResponse", canonical);
            assertEquals("CREATE_DASHBOARD", canonical.at("/steps/0/intentId").asText());
            assertEquals("AMEND", canonical.path("decision").asText());
        }
    }

    @Test void responseSchemaSeparatesResourceBindingsFromTheReadOnlyReviewSubject() throws Exception {
        var request = request();
        request.withArray("resourceChoices").addObject().put("ref", "review").put("kind", "dashboard-review")
                .put("label", "Overview").putObject("properties").put("reviewId", "review");
        var projection = new PrimaryPlannerProjection(mapper, request, validator.projection("plannerResponse"));
        var wrong = response();
        wrong.withObject("/steps/0/inputs/0/binding").withArray("refs").set(0, mapper.valueToTree("resource2"));
        assertThrows(ProtocolValidator.RegisteredViolation.class, () -> validator.validateRegistered(projection.schema(), wrong));
        wrong.withObject("/steps/0/inputs/0").put("field", "reviewId");
        assertThrows(ProtocolValidator.RegisteredViolation.class, () -> validator.validateRegistered(projection.schema(), wrong));
        validator.validateRegistered(projection.schema(), response());
    }

    @Test void activeSubjectComesOnlyFromTheExactStoredFocusAndDoesNotChangeCanonicalContext() {
        var request = request();
        var review = request.withArray("resourceChoices").addObject().put("ref", "review").put("kind", "dashboard-review").put("label", "Overview");
        review.putObject("properties").put("reviewId", "review").put("requiredIntent", "REFINE_DASHBOARD_REVIEW");
        assertFalse(new PrimaryPlannerProjection(mapper, request, validator.projection("plannerResponse")).request().has("activeSubject"));
        request.withObject("/dialogue").putObject("focus").put("reviewId", "review");
        var original = request.deepCopy();
        var wire = new PrimaryPlannerProjection(mapper, request, validator.projection("plannerResponse")).request();
        assertEquals("resource2", wire.at("/activeSubject/ref").asText());
        assertEquals("REFINE_DASHBOARD_REVIEW", wire.at("/activeSubject/requiredIntent").asText());
        assertEquals("ACTIVE_SUBJECT", wire.at("/resourceChoices/1/properties/role").asText());
        assertTrue(wire.at("/resourceChoices/1/properties/bindingFields").isEmpty());
        assertEquals("CONTEXT_RESOURCE", wire.at("/resourceChoices/0/properties/role").asText());
        assertEquals(original.path("dialogue"), wire.path("dialogue"));
        assertEquals(original, request);
        request.withObject("/dialogue/focus").put("reviewId", "unknown-review");
        assertFalse(new PrimaryPlannerProjection(mapper, request, validator.projection("plannerResponse")).request().has("activeSubject"));
    }

    @Test void loweringFailureRetainsUsageAndTheSameLedgerStoresCanonicalReplay() throws Exception {
        var f = new IntentScenarioSupport();
        var config = new AiModelConfig();
        config.setModelId("pinned-fixture");
        var transport = new PrimaryModelTransport(mapper, new AiResolvedModel(null, config, "not-on-wire"), null, null);
        String body = transport.serialize(request(), validator.projection("plannerResponse"), "JSON_TEXT");
        var size = ModelRouting.estimate(body, 1000, 64);
        var candidate = new AssistantProtocol.Candidate("provider", "pinned", null, 1_000_000L, 900_000L, 1000L, "JSON_TEXT", null);
        var route = new ModelRouting.Route(candidate, size, 500L, null, "PINNED_EXPLICIT");
        var limits = new AssistantProtocol.Limits(2L, 4_000_000L, 2000L, 60_000L, 1000L, "USD");
        var policy = new AssistantProtocol.RoutingPolicy("assistant-v2", "routing.policy", "policy", "1", "PINNED", "pinned",
                List.of(new AssistantProtocol.RoutingPolicyProfilesItem("primary", List.of(), List.of("PLANNER"), List.of(candidate), false)),
                limits, limits, "RESERVED_UPPER_BOUND", 500L, BigDecimal.ONE, null);
        var turn = new AttemptLedger.Turn(f.session.owner(), f.conversation, UUID.randomUUID(), UUID.randomUUID());
        var ledger = f.ledger();
        ledger.open(turn, policy);
        var correlation = new AssistantTraceRecorder.Correlation(turn.owner(), turn.conversationId(), UUID.randomUUID(), turn.turnId(), turn.turnId());
        var calls = new BudgetedModelCalls(ledger, validator, f.traces);
        var network = new AtomicInteger();
        var exchange = new BudgetedModelCalls.SingleExchange() {
            public BudgetedModelCalls.Reply send(String serialized, ModelRouting.Route selected, long timeout) throws Exception {
                var proposal = response();
                if (network.incrementAndGet() == 1) proposal.withObject("/steps/0/inputs/0/binding").withArray("refs").set(0, mapper.valueToTree("foreign-reference"));
                return new BudgetedModelCalls.Reply("JSON_TEXT", proposal.toString(), new AssistantProtocol.Usage(100L, 0L, 50L, 10L, null));
            }
            public JsonNode normalize(JsonNode proposal, String serialized) { return transport.normalize(proposal, serialized); }
        };
        var first = new AttemptLedger.Attempt(UUID.randomUUID(), "PLANNER", null, SkillRegistry.digest(body), "policy", "1", "catalogue-v1", "assistant-v2", "dialogue-v1", route, "PRIMARY");
        assertThrows(ModelProposalRejected.class, () -> calls.call(turn, first, body, "plannerResponse", correlation, exchange, proposal -> { }));
        var corrected = new AttemptLedger.Attempt(UUID.randomUUID(), "PLANNER", null, SkillRegistry.digest(body), "policy", "1", "catalogue-v1", "assistant-v2", "dialogue-v1", route, "FORMAT_CORRECTION");
        var result = calls.call(turn, corrected, body, "plannerResponse", correlation, exchange, proposal -> { });
        assertEquals(DEVICE, result.at("/steps/0/inputs/0/binding/value").asText());
        assertEquals(result, calls.call(turn, corrected, body, "plannerResponse", correlation, exchange, proposal -> { }));
        assertEquals(2, network.get(), "canonical replay must not re-lower the saved UUID binding or call a provider again");
        assertEquals(2, f.jdbc.queryForObject("select calls from ai_model_budget_plans where plan_id=?", Integer.class, turn.planId()));
        assertEquals(2, f.jdbc.queryForObject("select count(*) from ai_model_budget_attempts where plan_id=? and usage_json is not null", Integer.class, turn.planId()));
    }

    @Test void allOutputModesLowerScopedResourceAndServerSourceBeforeCanonicalValidation() throws Exception {
        var config = new AiModelConfig();
        config.setModelId("pinned-fixture");
        var transport = new PrimaryModelTransport(mapper, new AiResolvedModel(null, config, "never-on-wire"), null, null);
        var original = request();
        var before = original.deepCopy();
        for (String mode : List.of("JSON_TEXT", "STRICT_JSON", "NATIVE_TOOLS")) {
            var body = transport.serialize(original, validator.projection("plannerResponse"), mode);
            var input = mapper.readTree(mapper.readTree(body).path("input").asText());
            assertEquals("resource1", input.at("/resourceChoices/0/ref").asText());
            assertFalse(input.at("/resourceChoices/0/properties").has("deviceId"));
            assertEquals(original.path("dialogue"), input.path("dialogue"));
            var wire = response();
            String text = mode.equals("JSON_TEXT") ? wire.toString() : mapper.createObjectNode().set("proposal", wire).toString();
            var decoded = transport.complete(new com.voltcanary.aiagent.adapter.springai.SpringAiExchange.Result(text, null, null), mode, body);
            assertNull(decoded.completionCode());
            var canonical = transport.normalize(validator.parse(decoded.proposalJson()), body);
            validator.validate("plannerResponse", canonical);
            assertEquals(DEVICE, canonical.at("/steps/0/inputs/0/binding/value").asText());
            assertEquals("literal", canonical.at("/steps/0/inputs/0/binding/kind").asText());
            assertEquals(MESSAGE, canonical.at("/proposedGoals/0/sourceMessageId").asText());
            assertEquals(before, original);
            assertFalse(wire.at("/proposedGoals/0").has("sourceMessageId"));
        }
    }

    @Test void invalidReferencesSpoofedProvenanceAndDomainQuestionsAreRejectedWithoutGuessing() throws Exception {
        var projection = new PrimaryPlannerProjection(mapper, request(), validator.projection("plannerResponse"));
        var invalid = response();
        invalid.withObject("/steps/0/inputs/0/binding").withArray("refs").set(0, mapper.valueToTree("unknown"));
        assertThrows(ModelProposalRejected.class, () -> projection.normalize(invalid, validator));
        var literal = response();
        literal.withObject("/steps/0/inputs/0").set("binding", mapper.createObjectNode().put("kind", "literal").put("value", DEVICE));
        assertThrows(ModelProposalRejected.class, () -> projection.normalize(literal, validator));
        var spoof = response();
        spoof.withObject("/proposedGoals/0").put("sourceMessageId", "other-message");
        assertThrows(ModelProposalRejected.class, () -> projection.normalize(spoof, validator));
        var question = response();
        question.put("decision", "ASK");
        question.putArray("steps");
        question.putArray("proposedGoals");
        question.putObject("question").put("text", "Repeat the name?").putArray("missingFields").add("tramName");
        question.withObject("/question").putArray("choices");
        assertThrows(ModelProposalRejected.class, () -> projection.normalize(question, validator));
        question.withObject("/question").withArray("missingFields").set(0, mapper.valueToTree("intent"));
        validator.validate("plannerResponse", projection.normalize(question, validator));
    }

    @Test void newGoalIdCannotShadowAnExistingGoal() throws Exception {
        var request = request();
        request.withObject("/planSummary").putArray("goals")
                .addObject().put("goalId", "g1").put("disposition", "PENDING");
        var projection = new PrimaryPlannerProjection(mapper, request, validator.projection("plannerResponse"));
        var wire = response();
        assertThrows(ProtocolValidator.RegisteredViolation.class, () -> validator.validateRegistered(projection.schema(), wire));
        assertTrue(assertThrows(ModelProposalRejected.class, () -> projection.normalize(wire, validator))
                .diagnostic().contains("PLANNER_GOAL_ID_ALREADY_USED"));
        wire.withObject("/proposedGoals/0").put("goalId", "fresh");
        wire.withObject("/steps/0").withArray("goalIds").add("fresh");
        var normalized = projection.normalize(wire, validator);
        assertEquals(MESSAGE, normalized.at("/proposedGoals/0/sourceMessageId").asText());
        assertEquals(mapper.createArrayNode().add("g1").add("fresh"), normalized.at("/steps/0/goalIds"));
    }

    @Test void plannerContextListsOnlyAvailableExistingPrerequisites() throws Exception {
        var request = request();
        var steps = request.withObject("/planSummary").putArray("steps");
        steps.addObject().put("stepId", "device").put("status", "SUCCEEDED");
        steps.addObject().put("stepId", "old-dashboard").put("status", "SUPERSEDED");
        steps.addObject().put("stepId", "failed-source").put("status", "FAILED");
        var goals = request.withObject("/planSummary").putArray("goals");
        goals.addObject().put("goalId", "retained").put("disposition", "PENDING");
        goals.addObject().put("goalId", "old").put("disposition", "SUPERSEDED");
        var projection = new PrimaryPlannerProjection(mapper, request, validator.projection("plannerResponse"));
        assertEquals(mapper.createArrayNode().add("device"), projection.request().path("existingPrerequisiteStepIds"));
        assertEquals(mapper.createArrayNode().add("retained").add("old"), projection.request().path("existingGoalIds"));
        assertEquals(1, projection.request().path("pendingGoalCoverage").size());
    }

    @Test void askingToSelectTheOnlyAlreadySelectedDeviceIsRejected() throws Exception {
        var request = request();
        request.withObject("/resourceChoices/0/properties").put("selection", "UI_CONTEXT");
        var projection = new PrimaryPlannerProjection(mapper, request, validator.projection("plannerResponse"));
        assertEquals("resource1", projection.request().path("selectedDeviceRef").asText());
        assertTrue(projection.schema().at("/$defs/question/properties/missingFields/items/description")
                .asText().contains("already resolved"));
        var wire = response().put("decision", "ASK");
        wire.putArray("steps");
        wire.putArray("proposedGoals");
        var question = wire.putObject("question").put("text", "What is the dashboard name?");
        question.putArray("missingFields").add("target");
        question.putArray("choices").addObject().put("label", "Provide name")
                .putArray("bindings").addObject().put("field", "deviceId")
                .putObject("binding").put("kind", "resource_refs").putArray("refs").add("resource1");
        assertTrue(assertThrows(ModelProposalRejected.class, () -> projection.normalize(wire, validator))
                .diagnostic().contains("SELECTED_DEVICE_REQUIRES_SKILL_DISPATCH"));
        question.putArray("choices");
        assertTrue(assertThrows(ModelProposalRejected.class, () -> projection.normalize(wire, validator))
                .diagnostic().contains("SELECTED_DEVICE_REQUIRES_SKILL_DISPATCH"));
        question.withArray("missingFields").set(0, mapper.valueToTree("intent"));
        assertEquals("ASK", projection.normalize(wire, validator).path("decision").asText());
    }

    @Test void resourceKindMismatchFailsAndMultipleDeviceReferencesRemainAnArray() throws Exception {
        var request = request();
        request.withArray("resourceChoices").addObject().put("ref", "review").put("kind", "dashboard-review")
                .put("label", "Overview").putObject("properties").put("reviewId", "review");
        var projection = new PrimaryPlannerProjection(mapper, request, validator.projection("plannerResponse"));
        var wire = response();
        wire.withObject("/steps/0/inputs/0/binding").withArray("refs").set(0, mapper.valueToTree("resource2"));
        assertTrue(assertThrows(ModelProposalRejected.class, () -> projection.normalize(wire, validator)).diagnostic().contains("RESOURCE_REFERENCE_FIELD_MISMATCH"));
        var second = request.withArray("resourceChoices").addObject().put("ref", "second-device").put("kind", "device");
        second.putObject("properties").put("deviceId", "07e80f08-2756-46cb-974a-151fe700e7bc");
        var multiple = new PrimaryPlannerProjection(mapper, request, validator.projection("plannerResponse"));
        var good = response();
        good.withObject("/steps/0/inputs/0").put("field", "deviceIds");
        good.withObject("/steps/0/inputs/0/binding").withArray("refs").add("resource3");
        var canonical = multiple.normalize(good, validator);
        assertEquals(2, canonical.at("/steps/0/inputs/0/binding/value").size());
        assertEquals(good, multiple.correction(canonical));
        for (String binding : List.of("{\"kind\":\"fact_ref\",\"factId\":\"confirmed-device\"}",
                "{\"kind\":\"step_output\",\"stepId\":\"created-device\",\"outputKey\":\"deviceId\"}")) {
            var linked = response();
            linked.withObject("/steps/0/inputs/0").set("binding", mapper.readTree(binding));
            var input = mapper.readTree(binding);
            var lowered = multiple.normalize(linked, validator);
            assertEquals(input, lowered.at("/steps/0/inputs/0/binding"));
            if (input.path("kind").asText().equals("step_output")) {
                var expected = mapper.createArrayNode().add("created-device");
                assertEquals(expected, lowered.at("/steps/0/dependsOn"));
                linked.withObject("/steps/0").withArray("dependsOn").add("created-device");
                assertEquals(expected, multiple.normalize(linked, validator).at("/steps/0/dependsOn"));
            } else assertTrue(lowered.at("/steps/0/dependsOn").isEmpty());
        }
    }

    @Test void aBindingOfferedForAnotherIntentIsRejectedBeforeLowering() throws Exception {
        var request = request();
        request.withArray("catalogue").addObject().put("intentId", "REFINE_DASHBOARD_REVIEW").putArray("inputKeys");
        var projection = new PrimaryPlannerProjection(mapper, request, validator.projection("plannerResponse"));
        var wire = response();
        wire.withObject("/steps/0").put("intentId", "REFINE_DASHBOARD_REVIEW");
        assertThrows(ProtocolValidator.RegisteredViolation.class, () -> validator.validateRegistered(projection.schema(), wire));
        assertTrue(assertThrows(ModelProposalRejected.class, () -> projection.normalize(wire, validator))
                .diagnostic().contains("PLANNER_PARAMETER_NOT_ALLOWED"));
        wire.withObject("/steps/0").putArray("inputs");
        validator.validateRegistered(projection.schema(), wire);
        validator.validate("plannerResponse", projection.normalize(wire, validator));
    }

    @Test void strictProviderSchemasKeepStepBindingsScopedToTheSelectedIntent() throws Exception {
        var request = request();
        request.withArray("catalogue").addObject().put("intentId", "REFINE_DASHBOARD_REVIEW").putArray("inputKeys");
        var config = new AiModelConfig();
        config.setModelId("pinned-fixture");
        var transport = new PrimaryModelTransport(mapper, new AiResolvedModel(null, config, "fixture"), null, null);
        for (var mode : List.of("STRICT_JSON", "NATIVE_TOOLS")) {
            String serialized = transport.serialize(request, validator.projection("plannerResponse"), mode);
            var body = mapper.readTree(serialized);
            var schema = body.at(mode.equals("STRICT_JSON") ? "/text/format/schema" : "/tools/0/parameters");
            var proposal = response();
            var envelope = mapper.createObjectNode().set("proposal", proposal);
            validator.validateRegistered(schema, envelope);
            proposal.withObject("/steps/0").put("intentId", "REFINE_DASHBOARD_REVIEW");
            assertThrows(ProtocolValidator.RegisteredViolation.class, () -> validator.validateRegistered(schema, envelope));
            proposal.withObject("/steps/0").putArray("inputs");
            validator.validateRegistered(schema, envelope);
            var decoded = transport.complete(new com.voltcanary.aiagent.adapter.springai.SpringAiExchange.Result(
                    envelope.toString(), null, null), mode, serialized);
            assertNull(decoded.completionCode());
            var canonical = transport.normalize(validator.parse(decoded.proposalJson()), serialized);
            validator.validate("plannerResponse", canonical);
            assertTrue(canonical.at("/steps/0/inputs").isEmpty());
        }
    }

    @Test void goalsCannotBeEmptyAndEveryCompleteGuideExampleSatisfiesTheWireContract() throws Exception {
        var request = request();
        request.withArray("catalogue").addObject().put("intentId", "REFINE_DASHBOARD_REVIEW").putArray("inputKeys");
        var projection = new PrimaryPlannerProjection(mapper, request, validator.projection("plannerResponse"));
        var empty = response();
        empty.withObject("/steps/0").putArray("goalIds");
        assertThrows(ModelProposalRejected.class, () -> projection.normalize(empty, validator));
        int count = 0;
        for (String line : PrimaryPlannerGuide.text().lines().toList()) {
            if (!line.startsWith("{\"version\"")) continue;
            var example = projection.normalize(mapper.readTree(line), validator);
            validator.validate("plannerResponse", example);
            count++;
        }
        assertEquals(2, count);
    }

    private ObjectNode request() {
        var result = mapper.createObjectNode().put("kind", "planner.request").put("messageId", MESSAGE).put("message", "Exact current text");
        result.putObject("dialogue").put("pendingQuestion", "Exact question/options");
        result.putArray("catalogue").addObject().put("intentId", "CREATE_DASHBOARD").putArray("inputKeys")
                .add("deviceId").add("deviceIds").add("sourceId");
        var choice = result.putArray("resourceChoices").addObject().put("ref", DEVICE).put("kind", "device").put("label", "Selected device");
        choice.putObject("properties").put("deviceId", DEVICE);
        return result;
    }

    private ObjectNode response() throws Exception {
        return (ObjectNode) mapper.readTree("""
                {"version":"assistant-v2","kind":"planner.response","decision":"PLAN","explanation":"Prepare the selected dashboard",
                 "targetStepId":null,"supersedesStepIds":[],"question":null,
                 "steps":[{"stepId":"d1","intentId":"CREATE_DASHBOARD","dependsOn":[],
                   "inputs":[{"field":"deviceId","binding":{"kind":"resource_refs","refs":["resource1"]}}],"goalIds":["g1"]}],
                 "proposedGoals":[{"goalId":"g1","text":"Create the requested dashboard"}],"supersedesGoalIds":[]}
                """);
    }
}
