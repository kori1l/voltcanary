package com.voltcanary.aiagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.aiagent.adapter.JdbcAttemptLedger;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.Candidate;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.Limits;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.PriceSnapshot;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.RoutingPolicy;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.Usage;
import com.voltcanary.aiagent.orchestration.contract.ProtocolValidator;
import com.voltcanary.aiagent.orchestration.contract.SkillRegistry;
import com.voltcanary.aiagent.orchestration.routing.AttemptLedger;
import com.voltcanary.aiagent.orchestration.routing.BudgetedModelCalls;
import com.voltcanary.aiagent.orchestration.routing.ModelRouting;
import com.voltcanary.aiagent.orchestration.routing.ProviderProposalNormalizer;
import com.voltcanary.aiagent.orchestration.routing.RoutedModelCalls;
import com.voltcanary.aiagent.port.AssistantTraceRecorder;
import com.voltcanary.aiagent.service.AuthorizedPlanningContext.Scope;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

class AssistantRoutingBudgetScenarioTest {

    private static final Instant NOW = Instant.parse("2026-10-26T12:00:00Z");
    private static final String BODY =
        "{\"message\":\"второй\",\"dialogue\":\"actual question and choices\",\"instructions\":\"server\",\"schema\":{}}";
    private static final String RESPONSE =
        "{\"version\":\"assistant-v2\",\"kind\":\"step.response\",\"outcome\":\"REPLAN_REQUIRED\",\"explanation\":\"Новое намерение\"}";
    private final ObjectMapper mapper = new ObjectMapper();
    private final ProtocolValidator validator = new ProtocolValidator(mapper);
    private final ModelRouting.Size size = ModelRouting.estimate(BODY, 100, 64);

    @Test
    void pinnedCapacityAndDurableSharedAttemptScenario() throws Exception {
        var ds = new DriverManagerDataSource(
            "jdbc:h2:mem:" +
                UUID.randomUUID() +
                ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000",
            "sa",
            ""
        );
        var jdbc = new JdbcTemplate(ds);
        jdbc.execute("create domain if not exists timestamptz as timestamp with time zone");
        new ResourceDatabasePopulator(
            new ClassPathResource("db/migration/V004__assistant_conversations.sql"),
            new ClassPathResource("db/migration/V007__assistant_model_attempt_budgets.sql")
        ).execute(ds);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        var ledger = new JdbcAttemptLedger(
            jdbc,
            tx,
            mapper,
            java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC)
        );
        UUID owner = UUID.randomUUID();
        UUID conversation = UUID.randomUUID();
        jdbc.update(
            "insert into ai_conversations(id,user_id,account_id,title,revision,created_at,updated_at,expires_at) values(?,?,?,'QA',0,?,?,?)",
            conversation,
            owner,
            owner,
            Timestamp.from(NOW),
            Timestamp.from(NOW),
            Timestamp.from(NOW.plusSeconds(3600))
        );
        var turn = new AttemptLedger.Turn(
            new Scope(owner, owner),
            conversation,
            UUID.randomUUID(),
            UUID.randomUUID()
        );
        var policy = policy("AUTO", null, 2, "STOP");
        validator.validate("routingPolicy", mapper.valueToTree(policy));
        var available = availability(true, true, true);
        var route = ModelRouting.select(
            policy,
            "edit",
            "EDIT_DASHBOARD",
            "STEP",
            size,
            available,
            100_000,
            null,
            NOW
        );
        assertEquals("economical", route.candidate().modelConfigId());
        var pinned = policy("PINNED", "tiny", 2, "STOP");
        assertEquals(
            "CONTEXT_INSUFFICIENT",
            assertThrows(ModelRouting.Stop.class, () ->
                ModelRouting.select(
                    pinned,
                    "edit",
                    "EDIT_DASHBOARD",
                    "STEP",
                    size,
                    available,
                    100_000,
                    null,
                    NOW
                )
            ).getMessage()
        );
        assertThrows(ModelRouting.Stop.class, () ->
            ModelRouting.select(
                policy,
                "edit",
                "EDIT_DASHBOARD",
                "STEP",
                size,
                availability(true, false, true),
                100_000,
                null,
                NOW
            )
        );
        assertThrows(ModelRouting.Stop.class, () ->
            ModelRouting.select(
                policy,
                "edit",
                "EDIT_DASHBOARD",
                "STEP",
                size,
                availability(false, true, true),
                100_000,
                null,
                NOW
            )
        );
        assertThrows(ModelRouting.Stop.class, () ->
            ModelRouting.select(
                policy,
                "edit",
                "EDIT_DASHBOARD",
                "STEP",
                size,
                availability(true, true, false),
                100_000,
                null,
                NOW
            )
        );
        assertThrows(ModelRouting.Stop.class, () ->
            ModelRouting.select(
                policy,
                "edit",
                "EDIT_DASHBOARD",
                "STEP",
                size,
                available,
                0,
                null,
                NOW
            )
        );
        var unsupportedDocument =
            (com.fasterxml.jackson.databind.node.ObjectNode) mapper.valueToTree(policy);
        (
            (com.fasterxml.jackson.databind.node.ObjectNode) unsupportedDocument.at(
                "/profiles/0/candidates/1"
            )
        ).put("outputMode", "STRICT_JSON");
        var unsupportedMode = validator.read(
            "routingPolicy",
            unsupportedDocument,
            RoutingPolicy.class
        );
        assertEquals(
            "OUTPUT_MODE_UNSUPPORTED",
            assertThrows(ModelRouting.Stop.class, () ->
                ModelRouting.select(
                    unsupportedMode,
                    "edit",
                    "EDIT_DASHBOARD",
                    "STEP",
                    size,
                    available,
                    100_000,
                    null,
                    NOW
                )
            ).getMessage()
        );
        var boundedUnknown = ModelRouting.select(
            policy("AUTO", null, 2, "RESERVED_UPPER_BOUND"),
            "edit",
            "EDIT_DASHBOARD",
            "STEP",
            size,
            availability(true, true, false),
            100_000,
            null,
            NOW
        );
        assertEquals(500, boundedUnknown.costMicros());
        assertEquals(150, ModelRouting.cost(price("economical"), 100, 50, 50, 25));
        assertThrows(ModelRouting.Stop.class, () ->
            ModelRouting.cost(price("economical"), 100, 101, 50, 25)
        );

        ledger.open(turn, policy);
        var events = new ArrayList<String>();
        AssistantTraceRecorder recorder = (
            correlation,
            attempt,
            stage,
            status,
            code,
            duration,
            details
        ) -> events.add(stage + ":" + status);
        var calls = new BudgetedModelCalls(ledger, validator, recorder);
        var correlation = new AssistantTraceRecorder.Correlation(
            turn.owner(),
            conversation,
            UUID.randomUUID(),
            turn.turnId(),
            UUID.randomUUID()
        );
        var network = new AtomicInteger();
        var attempt = attempt(route, policy, UUID.randomUUID(), "STEP");
        var normalizer = new ProviderProposalNormalizer(validator);
        for (String mode : List.of("NATIVE_TOOLS", "STRICT_JSON", "JSON_TEXT")) {
            var proposal = mode.equals("JSON_TEXT")
                ? mapper.valueToTree(RESPONSE)
                : mapper.readTree(RESPONSE);
            assertEquals(
                mapper.readTree(RESPONSE),
                validator.parse(normalizer.normalize(mode, proposal, null).proposalJson())
            );
        }
        var response = calls.call(
            turn,
            attempt,
            BODY,
            "stepResponse",
            correlation,
            (body, selected, timeout) -> {
                network.incrementAndGet();
                return normalizer.normalize(
                    "JSON_TEXT",
                    mapper.valueToTree(RESPONSE),
                    new Usage(20L, 0L, 10L, 5L, null)
                );
            },
            ignored -> {}
        );
        assertEquals("REPLAN_REQUIRED", response.path("outcome").asText());
        assertEquals(1, network.get());
        long charged = jdbc.queryForObject(
            "select cost_micros from ai_model_budget_plans where plan_id=?",
            Long.class,
            turn.planId()
        );
        assertEquals(40, charged);
        // A new ledger instance simulates process restart. Duplicate delivery never re-enters the provider.
        var restarted = new JdbcAttemptLedger(
            jdbc,
            tx,
            mapper,
            java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC)
        );
        restarted.open(turn, policy);
        var repeated = new BudgetedModelCalls(restarted, validator, recorder).call(
            turn,
            attempt,
            BODY,
            "stepResponse",
            correlation,
            (body, selected, timeout) -> {
                throw new AssertionError("duplicate provider call");
            },
            ignored -> {}
        );
        assertEquals(response, repeated);
        assertEquals(4, events.size());

        // Competing repairs share the one remaining call at both levels.
        try (var pool = Executors.newFixedThreadPool(2)) {
            var futures = new ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int i = 0; i < 2; i++) futures.add(
                pool.submit(() -> {
                    try {
                        return restarted
                            .reserve(turn, attempt(route, policy, UUID.randomUUID(), "REPAIR"))
                            .newlyReserved();
                    } catch (ModelRouting.Stop exhausted) {
                        assertEquals("BUDGET_EXHAUSTED", exhausted.getMessage());
                        return false;
                    }
                })
            );
            int accepted = 0;
            for (var future : futures) if (future.get()) accepted++;
            assertEquals(1, accepted);
        }
        assertThrows(ModelRouting.Stop.class, () ->
            restarted.reserve(turn, attempt(route, policy, UUID.randomUUID(), "PLANNER"))
        );
        var nextTurn = new AttemptLedger.Turn(
            turn.owner(),
            conversation,
            turn.planId(),
            UUID.randomUUID()
        );
        restarted.open(nextTurn, policy);
        assertThrows(ModelRouting.Stop.class, () ->
            restarted.reserve(nextTurn, attempt(route, policy, UUID.randomUUID(), "STEP"))
        );
        UUID other = UUID.randomUUID();
        var foreign = new AttemptLedger.Turn(
            new Scope(other, other),
            conversation,
            turn.planId(),
            turn.turnId()
        );
        assertThrows(ModelRouting.Stop.class, () -> restarted.remainingCost(foreign));
        assertThrows(ModelRouting.Stop.class, () -> restarted.open(foreign, policy));

        // Unknown usage/network outcome keeps reservations and blocks replay, then cancellation rejects new work.
        var uncertain = new AttemptLedger.Turn(
            turn.owner(),
            conversation,
            UUID.randomUUID(),
            UUID.randomUUID()
        );
        restarted.open(uncertain, policy);
        var uncertainCorrelation = new AssistantTraceRecorder.Correlation(
            turn.owner(),
            conversation,
            UUID.randomUUID(),
            uncertain.turnId(),
            UUID.randomUUID()
        );
        var unknownAttempt = attempt(route, policy, UUID.randomUUID(), "STEP");
        var restartedCalls = new BudgetedModelCalls(restarted, validator, recorder);
        assertThrows(ModelRouting.Stop.class, () ->
            restartedCalls.call(
                uncertain,
                unknownAttempt,
                BODY,
                "stepResponse",
                uncertainCorrelation,
                (body, selected, timeout) -> {
                    network.incrementAndGet();
                    throw new java.io.IOException("timeout");
                },
                ignored -> {}
            )
        );
        assertEquals(
            route.costMicros(),
            jdbc.queryForObject(
                "select cost_micros from ai_model_budget_plans where plan_id=?",
                Long.class,
                uncertain.planId()
            )
        );
        assertThrows(ModelRouting.Stop.class, () ->
            restartedCalls.call(
                uncertain,
                unknownAttempt,
                BODY,
                "stepResponse",
                uncertainCorrelation,
                (body, selected, timeout) -> {
                    throw new AssertionError("uncertain replay");
                },
                ignored -> {}
            )
        );
        restarted.cancel(uncertain);
        assertThrows(ModelRouting.Stop.class, () ->
            restarted.reserve(uncertain, attempt(route, policy, UUID.randomUUID(), "REPAIR"))
        );
        assertTrue(events.contains("MODEL_CALL:UNKNOWN"));
        assertFalse(events.contains("MODEL_RUN:STARTED"));

        var late = new AttemptLedger.Turn(
            turn.owner(),
            conversation,
            UUID.randomUUID(),
            UUID.randomUUID()
        );
        restarted.open(late, policy);
        var lateAttempt = attempt(route, policy, UUID.randomUUID(), "STEP");
        restarted.reserve(late, lateAttempt);
        restarted.cancel(late);
        assertThrows(ModelRouting.Stop.class, () ->
            restarted.finish(late, lateAttempt.callId(), "SUCCEEDED", null, RESPONSE)
        );
        assertEquals(
            null,
            jdbc.queryForObject(
                "select response_json from ai_model_budget_attempts where plan_id=?",
                String.class,
                late.planId()
            )
        );
        var elapsed = new JdbcAttemptLedger(
            jdbc,
            tx,
            mapper,
            java.time.Clock.fixed(NOW.plusSeconds(61), java.time.ZoneOffset.UTC)
        );
        assertThrows(ModelRouting.Stop.class, () ->
            elapsed.reserve(turn, attempt(route, policy, UUID.randomUUID(), "STEP"))
        );

        var traceFailure = new AttemptLedger.Turn(
            turn.owner(),
            conversation,
            UUID.randomUUID(),
            UUID.randomUUID()
        );
        restarted.open(traceFailure, policy);
        var traceCorrelation = new AssistantTraceRecorder.Correlation(
            turn.owner(),
            conversation,
            UUID.randomUUID(),
            traceFailure.turnId(),
            UUID.randomUUID()
        );
        var failedTraceCalls = new BudgetedModelCalls(
            restarted,
            validator,
            (c, a, s, state, code, duration, detail) -> {
                throw new IllegalStateException("trace offline");
            }
        );
        assertThrows(IllegalStateException.class, () ->
            failedTraceCalls.call(
                traceFailure,
                attempt(route, policy, UUID.randomUUID(), "STEP"),
                BODY,
                "stepResponse",
                traceCorrelation,
                (body, selected, timeout) -> {
                    throw new AssertionError("trace failure allowed provider");
                },
                ignored -> {}
            )
        );
        var lowInputDocument = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.valueToTree(
            policy
        );
        ((com.fasterxml.jackson.databind.node.ObjectNode) lowInputDocument.path("turnLimits")).put(
            "maxInputTokens",
            1
        );
        var lowInput = validator.read("routingPolicy", lowInputDocument, RoutingPolicy.class);
        var inputLimited = new AttemptLedger.Turn(
            turn.owner(),
            conversation,
            UUID.randomUUID(),
            UUID.randomUUID()
        );
        restarted.open(inputLimited, lowInput);
        assertEquals(
            "BUDGET_EXHAUSTED",
            assertThrows(ModelRouting.Stop.class, () ->
                restarted.reserve(inputLimited, attempt(route, lowInput, UUID.randomUUID(), "STEP"))
            ).getMessage()
        );

        // Native provider envelopes and JSON text converge to the identical untrusted proposal.
        var openAi = mapper.createObjectNode();
        var choice = openAi.putArray("choices").addObject();
        choice.put("finish_reason", "tool_calls");
        var function = choice
            .putObject("message")
            .putArray("tool_calls")
            .addObject()
            .putObject("function");
        function.put("name", "assistant_proposal").put("arguments", RESPONSE);
        assertEquals(
            mapper.readTree(RESPONSE),
            validator.parse(normalizer.openAi(openAi, "NATIVE_TOOLS", null).proposalJson())
        );
        choice.put("finish_reason", "length");
        assertThrows(ModelRouting.Stop.class, () ->
            normalizer.openAi(openAi, "NATIVE_TOOLS", null)
        );
        var anthropic = mapper.createObjectNode().put("stop_reason", "tool_use");
        anthropic
            .putArray("content")
            .addObject()
            .put("type", "tool_use")
            .put("name", "assistant_proposal")
            .set("input", mapper.readTree(RESPONSE));
        assertEquals(
            mapper.readTree(RESPONSE),
            validator.parse(normalizer.anthropic(anthropic, "NATIVE_TOOLS", null).proposalJson())
        );
        var gemini = mapper.createObjectNode();
        gemini
            .putArray("candidates")
            .addObject()
            .put("finishReason", "STOP")
            .putObject("content")
            .putArray("parts")
            .addObject()
            .putObject("functionCall")
            .put("name", "assistant_proposal")
            .set("args", mapper.readTree(RESPONSE));
        assertEquals(
            mapper.readTree(RESPONSE),
            validator.parse(normalizer.gemini(gemini, "NATIVE_TOOLS", null).proposalJson())
        );

        // The application seam validates canonical requests and sizes the final serialized body.
        var coordinatedTurn = new AttemptLedger.Turn(
            turn.owner(),
            conversation,
            UUID.randomUUID(),
            UUID.randomUUID()
        );
        var coordinatedCorrelation = new AssistantTraceRecorder.Correlation(
            turn.owner(),
            conversation,
            UUID.randomUUID(),
            coordinatedTurn.turnId(),
            UUID.randomUUID()
        );
        com.fasterxml.jackson.databind.node.ObjectNode planner;
        com.fasterxml.jackson.databind.node.ObjectNode manifest;
        try (
            var stream = getClass().getResourceAsStream(
                "/assistant-v2/ai-assistant-protocol.examples.json"
            )
        ) {
            var examples = mapper.readTree(stream).path("cases");
            planner = (com.fasterxml.jackson.databind.node.ObjectNode) examples
                .get(1)
                .path("document");
            manifest = (com.fasterxml.jackson.databind.node.ObjectNode) examples
                .get(7)
                .path("document");
        }
        ((com.fasterxml.jackson.databind.node.ObjectNode) manifest.path("instructions")).put(
            "digest",
            SkillRegistry.digest(manifest.at("/instructions/text").asText())
        );
        var fixtureRegistry = new SkillRegistry(
            validator,
            List.of(
                new SkillRegistry.Package(
                    manifest,
                    Map.of(
                        manifest.path("inputSchemaId").asText(),
                        mapper.readTree("{\"type\":\"object\"}"),
                        manifest.path("outputSchemaId").asText(),
                        validator.projection("stepResponse"),
                        manifest.path("commandSchemaId").asText(),
                        mapper.readTree("{\"type\":\"object\"}")
                    ),
                    Set.of(),
                    Map.of(
                        "dashboard.current-layout",
                        manifest.path("inputSchemaId").asText(),
                        "dashboard.data-choices",
                        manifest.path("inputSchemaId").asText()
                    )
                )
            )
        );
        var coordinator = new RoutedModelCalls(
            mapper,
            validator,
            fixtureRegistry,
            restarted,
            recorder,
            java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC)
        );
        planner.set(
            "catalogue",
            mapper.valueToTree(fixtureRegistry.catalogue(Set.of("EDIT_DASHBOARD"), Set.of()))
        );
        var selection = new RoutedModelCalls.Selection("edit", "PLANNER", null, 100, 64);
        var adapter = new RoutedModelCalls.WireAdapter() {
            public String serialize(
                com.fasterxml.jackson.databind.JsonNode request,
                com.fasterxml.jackson.databind.JsonNode schema,
                String mode
            ) {
                return mapper
                    .createObjectNode()
                    .setAll(Map.of("request", request, "responseSchema", schema))
                    .toString();
            }

            public BudgetedModelCalls.Reply send(
                String body,
                ModelRouting.Route selected,
                long timeout
            ) {
                network.incrementAndGet();
                assertTrue(body.contains(planner.path("message").asText()));
                assertTrue(timeout > 0 && timeout <= 60_000);
                return new BudgetedModelCalls.Reply(
                    "JSON_TEXT",
                    "{\"version\":\"assistant-v2\",\"kind\":\"planner.response\",\"decision\":\"UNSUPPORTED\",\"explanation\":\"No registered skills\",\"targetStepId\":null,\"supersedesStepIds\":[],\"supersedesGoalIds\":[],\"question\":null,\"steps\":[],\"proposedGoals\":[]}",
                    null
                );
            }
        };
        UUID coordinatedCall = UUID.randomUUID();
        var proposed = coordinator.call(
            coordinatedTurn,
            coordinatedCall,
            mapper.valueToTree(policy),
            planner,
            selection,
            Set.of("EDIT_DASHBOARD"),
            Set.of(),
            available,
            coordinatedCorrelation,
            adapter
        );
        assertEquals("UNSUPPORTED", proposed.path("decision").asText());
        int beforeDuplicate = network.get();
        coordinator.call(
            coordinatedTurn,
            coordinatedCall,
            mapper.valueToTree(policy),
            planner,
            selection,
            Set.of("EDIT_DASHBOARD"),
            Set.of(),
            available,
            coordinatedCorrelation,
            adapter
        );
        assertEquals(beforeDuplicate, network.get());

        var accessChecks = new java.util.concurrent.atomic.AtomicInteger();
        var deniedCall = UUID.randomUUID();
        var denied = assertThrows(ModelRouting.Stop.class, () ->
            coordinator.call(
                coordinatedTurn,
                deniedCall,
                mapper.valueToTree(policy),
                planner,
                selection,
                Set.of("EDIT_DASHBOARD"),
                Set.of(),
                available,
                coordinatedCorrelation,
                adapter,
                null,
                ignored -> {},
                prepared -> {
                    if (accessChecks.incrementAndGet() == 2) {
                        throw new ModelRouting.Stop("KNOWLEDGE_EVIDENCE_ACCESS_CHANGED");
                    }
                }
            )
        );
        assertEquals("KNOWLEDGE_EVIDENCE_ACCESS_CHANGED", denied.getMessage());
        assertEquals(2, accessChecks.get());
        assertEquals(beforeDuplicate, network.get());
        assertEquals(
            "FAILED",
            jdbc.queryForObject(
                "select status from ai_model_budget_attempts where call_id=?",
                String.class,
                deniedCall
            )
        );
    }

    private static AttemptLedger.Attempt attempt(
        ModelRouting.Route route,
        RoutingPolicy policy,
        UUID id,
        String phase
    ) {
        return new AttemptLedger.Attempt(
            id,
            phase,
            "edit",
            SkillRegistry.digest(BODY),
            policy.policyId(),
            policy.policyVersion(),
            "1",
            "assistant-v2",
            "1",
            route
        );
    }

    private static RoutingPolicy policy(
        String mode,
        String pinned,
        long maxCalls,
        String unknownPrice
    ) {
        var limits = new Limits(maxCalls, 100_000L, 100_000L, 60_000L, 100_000L, "USD");
        return new RoutingPolicy(
            "assistant-v2",
            "routing.policy",
            "qa-policy",
            "1",
            mode,
            pinned,
            List.of(
                new AssistantProtocol.RoutingPolicyProfilesItem(
                    "edit",
                    List.of("EDIT_DASHBOARD"),
                    List.of("PLANNER", "STEP", "REPAIR"),
                    List.of(
                        new Candidate(
                            "provider",
                            "tiny",
                            "quality-1",
                            32L,
                            32L,
                            100L,
                            "JSON_TEXT",
                            "price-tiny"
                        ),
                        new Candidate(
                            "provider",
                            "economical",
                            "quality-1",
                            100_000L,
                            100_000L,
                            1000L,
                            "JSON_TEXT",
                            "price-economical"
                        )
                    ),
                    true
                )
            ),
            limits,
            limits,
            unknownPrice,
            unknownPrice.equals("STOP") ? null : 500L,
            BigDecimal.ONE,
            null
        );
    }

    private static PriceSnapshot price(String model) {
        return new PriceSnapshot(
            "assistant-v2",
            "price.snapshot",
            "price-" + model,
            "provider",
            model,
            "USD",
            NOW.minusSeconds(60).toString(),
            NOW.plusSeconds(3600).toString(),
            "CONFIGURED",
            "offline synthetic rates",
            new AssistantProtocol.PriceSnapshotRates(
                new BigDecimal("1000000"),
                BigDecimal.ZERO,
                new BigDecimal("2000000"),
                BigDecimal.ZERO
            ),
            true
        );
    }

    private static ModelRouting.Availability availability(
        boolean authorized,
        boolean qualified,
        boolean prices
    ) {
        return new ModelRouting.Availability() {
            public boolean authorized(String provider, String model) {
                return authorized;
            }

            public boolean qualified(
                String id,
                String model,
                String profile,
                String intent,
                String phase
            ) {
                return qualified;
            }

            public Set<String> outputModes(String model) {
                return Set.of("JSON_TEXT");
            }

            public PriceSnapshot price(String id) {
                return prices
                    ? AssistantRoutingBudgetScenarioTest.price(id.substring("price-".length()))
                    : null;
            }
        };
    }
}
