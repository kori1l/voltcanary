package com.voltcanary.aiagent;

import com.voltcanary.aiagent.adapter.langgraph4j.LangGraphAgentRunner;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol;
import com.voltcanary.aiagent.orchestration.plan.AgentGraphRunner;
import com.voltcanary.aiagent.orchestration.plan.IntentPlans;
import com.voltcanary.aiagent.orchestration.plan.OrchestrationPorts;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LangGraphAgentIntegrationTest {
    @Test
    void libraryOwnsConditionalTraversalAndKeepsTheCallerContext() {
        var runner = new LangGraphAgentRunner();
        var caller = Thread.currentThread();
        var visits = new ArrayList<String>();
        var checkpoints = new ArrayList<AgentGraphRunner.Checkpoint>();
        var rounds = new AtomicInteger();
        runner.run("decide", List.of(
                new AgentGraphRunner.Node("decide", () -> {
                    assertThat(Thread.currentThread()).isSameAs(caller);
                    visits.add("decide");
                    return rounds.incrementAndGet() < 2 ? "context" : AgentGraphRunner.END;
                }, Set.of("context", AgentGraphRunner.END)),
                new AgentGraphRunner.Node("context", () -> {
                    visits.add("context");
                    return "decide";
                }, Set.of("decide"))), "isolated", new AgentGraphRunner.Persistence() {
                    public java.util.Optional<AgentGraphRunner.Checkpoint> load() { return java.util.Optional.empty(); }
                    public void save(AgentGraphRunner.Checkpoint checkpoint) { checkpoints.add(checkpoint); }
                });
        assertThat(visits).containsExactly("decide", "context", "decide");
        assertThat(checkpoints.getLast().next()).isEqualTo(AgentGraphRunner.END);
        assertThat(checkpoints).allSatisfy(cp -> assertThat(cp.values().keySet()).isSubsetOf("route"));
    }

    @Test
    void libraryBoundDoesNotRetryFailedNodesOrRunIndefinitely() {
        var calls = new AtomicInteger();
        var failure = IntentPlans.conflict("CANCELLED_FIXTURE");
        assertThatThrownBy(() -> new LangGraphAgentRunner().run("call", List.of(
                new AgentGraphRunner.Node("call", () -> { calls.incrementAndGet(); throw failure; }, Set.of(AgentGraphRunner.END))),
                "failed", AgentGraphRunner.inTurn())).isSameAs(failure);
        assertThat(calls).hasValue(1);

        calls.set(0);
        assertThatThrownBy(() -> new LangGraphAgentRunner().run("loop", List.of(
                new AgentGraphRunner.Node("loop", () -> { calls.incrementAndGet(); return "loop"; }, Set.of("loop"))),
                "bounded", AgentGraphRunner.inTurn())).isInstanceOf(RuntimeException.class);
        assertThat(calls.get()).isBetween(1, 32);
    }

    @Test
    void questionAndCheckpointRestartTogetherWithoutReplayingTheModel() throws Exception {
        verifyQuestionRestart(new IntentScenarioSupport());
    }

    static void verifyQuestionRestart(IntentScenarioSupport f) throws Exception {
        var calls = new AtomicInteger();
        var model = questionModel(f, calls);
        var request = f.submit("Нужно уточнение", 0, null);
        var state = f.orchestration(model).submit(f.session, f.conversation, null, request, "policy", "1", f.skills);
        assertThat(state.status()).isEqualTo("WAITING_INPUT");
        assertThat(state.execution().engine()).isEqualTo(LangGraphAgentRunner.ENGINE);
        assertThat(state.execution().next()).isEqualTo(AgentGraphRunner.END);
        assertThat(state.execution().requestId()).isEqualTo(request.requestId());
        assertThat(f.jdbc.queryForObject("select schema_version from ai_orchestrations where id=?", Integer.class, state.planId())).isEqualTo(2);
        f.restart();
        var restored = f.store.read(f.session.owner(), state.planId());
        assertThat(restored).isEqualTo(state);
        var replay = f.orchestration(model).submit(f.session, f.conversation, state.planId(), request, "policy", "1", f.skills);
        assertThat(replay).isEqualTo(state);
        assertThat(calls).hasValue(1);
        assertThat(f.mutations).isEmpty();
    }

    @Test
    void unknownEngineAndLegacyPlansCannotSilentlySwitchGenerators() throws Exception {
        var f = new IntentScenarioSupport();
        var calls = new AtomicInteger();
        var model = questionModel(f, calls);
        var original = f.submit("Уточни", 0, null);
        var state = f.orchestration(model).submit(f.session, f.conversation, null, original, "policy", "1", f.skills);
        var pin = state.execution();
        var incompatible = state.execution(new IntentPlans.GraphExecution("other-engine", pin.definition(), pin.requestId(),
                pin.checkpointId(), pin.node(), pin.next(), pin.values()));
        f.jdbc.update("update ai_orchestrations set state_json=? where id=?", f.mapper.writeValueAsString(incompatible), state.planId());
        assertThat(f.orchestration(model).submit(f.session, f.conversation, state.planId(), original, "policy", "1", f.skills))
                .isEqualTo(incompatible);
        assertThatThrownBy(() -> f.send(f.orchestration(model), incompatible, "Ответ"))
                .isInstanceOf(IntentPlans.Rejected.class).hasMessage("GRAPH_ENGINE_VERSION_CHANGED");
        var legacy = state.execution(null);
        f.jdbc.update("update ai_orchestrations set state_json=?,schema_version=1 where id=?", f.mapper.writeValueAsString(legacy), state.planId());
        assertThat(f.store.read(f.session.owner(), state.planId()).execution()).isNull();
        assertThatThrownBy(() -> f.send(f.orchestration(model), legacy, "Ответ"))
                .isInstanceOf(IntentPlans.Rejected.class).hasMessage("GRAPH_ENGINE_MIGRATION_REQUIRED");
        assertThat(calls).hasValue(1);
    }

    @Test
    void concurrentResumeCannotLaunchAnotherPlanner() throws Exception {
        verifyConcurrentResume(new IntentScenarioSupport());
    }

    static void verifyConcurrentResume(IntentScenarioSupport f) throws Exception {
        var calls = new AtomicInteger();
        var initial = questionModel(f, calls);
        var state = f.send(f.orchestration(initial), null, "Уточни");
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var blocking = new OrchestrationPorts.Models() {
            public AssistantProtocol.PlannerResponse plan(IntentPlans.ModelInput input, OrchestrationPorts.Session session, UUID call) {
                entered.countDown();
                try {
                    if (!release.await(20, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("FIXTURE_TIMEOUT");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
                return initial.plan(input, session, call);
            }
            public AssistantProtocol.StepResponse step(IntentPlans.ModelInput input, OrchestrationPorts.Session session, UUID call) {
                throw new AssertionError("No step expected");
            }
        };
        try (var pool = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var request = f.submit("Ответ", state.revision(), state.pendingQuestion().questionId());
            var first = pool.submit(() -> f.orchestration(blocking).submit(f.session, f.conversation, state.planId(), request, "policy", "1", f.skills));
            try {
                assertThat(entered.await(20, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> f.send(f.orchestration(initial), state, "Другой ответ"))
                        .isInstanceOf(IntentPlans.Rejected.class).hasMessage("REVISION_CHANGED");
            } finally {
                release.countDown();
            }
            var saved = first.get(20, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(saved.execution().requestId()).isEqualTo(request.requestId());
            assertThat(f.store.read(f.session.owner(), state.planId())).isEqualTo(saved);
            assertThat(calls).hasValue(2);
        }
    }

    @Test
    void checkpointFailureCannotCommitAQuestionWithoutItsCheckpoint() throws Exception {
        verifyAtomicRollback(new IntentScenarioSupport());
    }

    static void verifyAtomicRollback(IntentScenarioSupport f) throws Exception {
        f.jdbc.execute("alter table ai_orchestrations add constraint reject_finish check (state_json not like '%\"node\":\"finish\"%')");
        var calls = new AtomicInteger();
        var model = questionModel(f, calls);
        var request = f.submit("Спроси", 0, null);
        assertThatThrownBy(() -> f.orchestration(model).submit(f.session, f.conversation, null, request, "policy", "1", f.skills))
                .isInstanceOf(RuntimeException.class);
        UUID id = UUID.nameUUIDFromBytes((f.conversation + ":" + request.requestId()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        f.restart();
        var stored = f.store.read(f.session.owner(), id);
        assertThat(stored.execution().node()).isEqualTo("plan");
        assertThat(stored.pendingQuestion()).isNotNull();
        assertThat(stored.execution().next()).isEqualTo("finish");
        assertThat(f.jdbc.queryForObject("select status from ai_orchestration_requests where plan_id=?", String.class, id)).isEqualTo("PENDING");
        f.orchestration(model).submit(f.session, f.conversation, id, request, "policy", "1", f.skills);
        assertThat(calls).hasValue(1);
        assertThat(f.mutations).isEmpty();
    }

    static OrchestrationPorts.Models questionModel(IntentScenarioSupport f, AtomicInteger calls) {
        return new OrchestrationPorts.Models() {
            public AssistantProtocol.PlannerResponse plan(IntentPlans.ModelInput input, OrchestrationPorts.Session session, UUID call) {
                calls.incrementAndGet();
                return new AssistantProtocol.PlannerResponse("assistant-v2", "planner.response", "ASK", "Уточните", null, List.of(),
                        new AssistantProtocol.Question("Как назвать?", List.of("name"), List.of()), List.of(), List.of(), List.of());
            }
            public AssistantProtocol.StepResponse step(IntentPlans.ModelInput input, OrchestrationPorts.Session session, UUID call) {
                throw new AssertionError("No step may run before the question is answered");
            }
        };
    }
}
