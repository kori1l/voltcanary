package com.voltcanary.aiagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.voltcanary.aiagent.adapter.langgraph4j.LangGraphAgentRunner;
import com.voltcanary.aiagent.orchestration.collaboration.CollaborationStore;
import com.voltcanary.aiagent.orchestration.collaboration.ConsultationContracts;
import com.voltcanary.aiagent.orchestration.collaboration.ConsultationPolicy;
import com.voltcanary.aiagent.orchestration.collaboration.ConsultationWorker;
import com.voltcanary.aiagent.orchestration.collaboration.Consultations;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol;
import com.voltcanary.aiagent.orchestration.contract.SkillRegistry;
import com.voltcanary.aiagent.orchestration.routing.BudgetedModelCalls;
import com.voltcanary.aiagent.orchestration.routing.ModelRouting;
import com.voltcanary.aiagent.orchestration.routing.RoutedModelCalls;
import com.voltcanary.aiagent.port.AssistantTraceRecorder;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConsultationWorkerTest {
    @Test
    void nativeSubgraphClaimsBeforeSharedReservationAndReplaysWithoutNetwork() throws Exception {
        verify(false);
    }

    @Test
    void uncertainExchangeKeepsReservationAndCannotBeAutomaticallyRetried() throws Exception {
        verify(true);
    }

    @Test
    void duplicateWorkerCannotFinishOrResendAnInFlightReservation() throws Exception {
        verify(CollaborationStoreTest.fixture(new IntentScenarioSupport()), false, true);
    }

    private void verify(boolean uncertain) throws Exception {
        verify(CollaborationStoreTest.fixture(new IntentScenarioSupport()), uncertain);
    }

    static void verify(CollaborationStoreTest.Fixture fixture, boolean uncertain) {
        verify(fixture, uncertain, false);
    }

    static void verify(CollaborationStoreTest.Fixture fixture, boolean uncertain, boolean concurrent) {
        var f = fixture.f();
        var state = fixture.store().update(fixture.turn(), 0, "requirements", SkillRegistry.digest("requirements"),
                (before, now) -> CollaborationStoreTest.propose(fixture, before));
        var role = new Consultations.Role("schema", "1", "result-v1", Consultations.Reason.SCHEMA_REASONING,
                f.mapper.createObjectNode().put("type", "object").put("additionalProperties", false),
                Set.of(), Set.of(), Set.of("/name"), "Assess the declared name requirement.");
        var contracts = new ConsultationContracts(f.validator, f.mapper, List.of(role), Map.of(),
                (schema, value) -> f.validator.validate("collab.VclLiteral", value));
        var policy = new ConsultationPolicy(contracts, f.mapper);
        UUID goal = UUID.randomUUID();
        var request = new Consultations.Request(fixture.turn().planId(), "assess", "schema", "1", List.of(goal),
                state.requirements().revision(), List.of(), "Assess the name", "result-v1");
        state = fixture.store().update(fixture.turn(), state.revision(), "request", SkillRegistry.digest("request"), (before, now) ->
                new CollaborationStore.State(before.revision() + 1, before.requirements(), policy.request(before.consultations(), request,
                        before.requirements(), Set.of(goal), Set.of(), "authority-1")));
        UUID taskId = state.consultations().tasks().keySet().iterator().next();
        var ledger = f.ledger();
        var calls = new RoutedModelCalls(f.mapper, f.validator, f.registry, ledger, f.traces, Clock.systemUTC());
        var count = new AtomicInteger();
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var adapter = new RoutedModelCalls.WireAdapter() {
            public String serialize(JsonNode input, JsonNode schema, String outputMode) {
                var document = f.mapper.createObjectNode();
                document.set("input", input);
                document.set("schema", schema);
                return document.toString();
            }
            public BudgetedModelCalls.Reply send(String body, ModelRouting.Route route, long timeout) throws Exception {
                count.incrementAndGet();
                assertThat(f.jdbc.queryForObject("select count(*) from ai_model_budget_attempts where status='STARTED'", Long.class)).isEqualTo(1);
                assertThat(fixture.store().read(fixture.turn().owner(), fixture.turn().planId()).consultations().tasks().get(taskId).status())
                        .isEqualTo(Consultations.Status.RUNNING);
                assertThat(timeout).isBetween(1L, 240_000L);
                if (concurrent) {
                    entered.countDown();
                    if (!release.await(20, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("FIXTURE_TIMEOUT");
                }
                if (uncertain) throw new java.io.IOException("controlled unknown transport outcome");
                var input = f.mapper.readTree(body).path("input");
                var answer = f.mapper.createObjectNode().put("taskId", taskId.toString()).put("attemptId", input.path("attemptId").asText())
                        .put("requirementsRevision", input.path("requirementsRevision").asLong()).put("readSetDigest", input.path("readSetDigest").asText())
                        .put("outcome", "ANSWER");
                answer.putObject("payload");
                answer.putArray("evidenceRefs");
                answer.putArray("unresolvedSlotIds").add(fixture.slot().toString());
                return new BudgetedModelCalls.Reply("JSON_TEXT", answer.toString(), null);
            }
        };
        var worker = new ConsultationWorker(fixture.store(), policy, contracts, calls, ledger, new LangGraphAgentRunner(), f.mapper, f.validator);
        var limits = new AssistantProtocol.Limits(2L, 1_000_000L, 24_000L, 240_000L, 200_000L, "USD");
        var routePolicy = new AssistantProtocol.RoutingPolicy("assistant-v2", "routing.policy", "policy", "1", "PINNED", "fixture-model",
                List.of(new AssistantProtocol.RoutingPolicyProfilesItem("primary", List.of("schema"), List.of("CONSULT"),
                        List.of(new AssistantProtocol.Candidate("fixture-provider", "fixture-model", null, 128_000L, 112_000L, 12_000L, "JSON_TEXT", null)), false)),
                limits, limits, "RESERVED_UPPER_BOUND", 100_000L, BigDecimal.ONE, null);
        var availability = new ModelRouting.Availability() {
            public boolean authorized(String provider, String model) { return true; }
            public boolean qualified(String id, String model, String profile, String intent, String phase) { return true; }
            public Set<String> outputModes(String model) { return Set.of("JSON_TEXT"); }
            public AssistantProtocol.PriceSnapshot price(String id) { return null; }
        };
        var correlation = new AssistantTraceRecorder.Correlation(fixture.turn().owner(), f.conversation,
                fixture.turn().messageId(), fixture.turn().requestId(), fixture.turn().requestId());
        var route = new ConsultationWorker.Route(f.mapper.valueToTree(routePolicy), new RoutedModelCalls.Selection("primary", "CONSULT", null, 12_000, 4096),
                availability, correlation, adapter);
        ConsultationWorker.Authority authority = (turn, task) -> new ConsultationWorker.Evidence(Map.of(), Map.of(), Set.of(), "authority-1");
        CollaborationStore.State result;
        if (concurrent) {
            try (var pool = java.util.concurrent.Executors.newSingleThreadExecutor()) {
                var first = pool.submit(() -> worker.run(fixture.turn(), taskId, 0, authority, route));
                try {
                    assertThat(entered.await(20, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                    var duplicate = worker.run(fixture.turn(), taskId, 0, authority, route);
                    assertThat(duplicate.consultations().tasks().get(taskId).status()).isEqualTo(Consultations.Status.RUNNING);
                    var conflictingAdapter = new RoutedModelCalls.WireAdapter() {
                        public String serialize(JsonNode input, JsonNode schema, String mode) {
                            return adapter.serialize(input, schema, mode) + " ";
                        }
                        public BudgetedModelCalls.Reply send(String body, ModelRouting.Route selected, long timeout) {
                            throw new AssertionError("Conflicting replay cannot send");
                        }
                    };
                    var conflictingRoute = new ConsultationWorker.Route(route.policy(), route.selection(), route.availability(), route.correlation(), conflictingAdapter);
                    assertThatThrownBy(() -> worker.run(fixture.turn(), taskId, 0, authority, conflictingRoute)).hasMessage("CALL_ID_CONFLICT");
                    assertThat(fixture.store().read(fixture.turn().owner(), fixture.turn().planId()).consultations().tasks().get(taskId).status())
                            .isEqualTo(Consultations.Status.RUNNING);
                    assertThat(f.jdbc.queryForObject("select status from ai_model_budget_attempts", String.class)).isEqualTo("STARTED");
                } finally {
                    release.countDown();
                }
                result = first.get(20, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
        } else result = worker.run(fixture.turn(), taskId, 0, authority, route);
        assertThat(result.consultations().tasks().get(taskId).status()).isEqualTo(uncertain ? Consultations.Status.FAILED : Consultations.Status.SUCCEEDED);
        assertThat(f.jdbc.queryForObject("select status from ai_model_budget_attempts", String.class)).isEqualTo(uncertain ? "UNCERTAIN" : "SUCCEEDED");
        assertThat(f.jdbc.queryForObject("select calls from ai_model_budget_plans", Long.class)).isEqualTo(1);
        var restarted = new ConsultationWorker(fixture.store(), policy, contracts, calls, ledger, new LangGraphAgentRunner(), f.mapper, f.validator);
        assertThat(restarted.run(fixture.turn(), taskId, 0, authority, route)).isEqualTo(result);
        assertThat(count).hasValue(1);
        if (uncertain) assertThat(result.consultations().tasks().get(taskId).result().path("retryClass").asText()).isEqualTo("UNKNOWN_OUTCOME");
    }
}
