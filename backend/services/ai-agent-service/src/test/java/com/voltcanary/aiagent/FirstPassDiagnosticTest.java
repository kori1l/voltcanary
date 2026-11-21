package com.voltcanary.aiagent;

import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol;
import com.voltcanary.aiagent.orchestration.plan.IntentOrchestration;
import com.voltcanary.aiagent.orchestration.plan.IntentPlans;
import com.voltcanary.aiagent.orchestration.plan.OrchestrationPorts;
import com.voltcanary.aiagent.orchestration.plan.StepPreparationFailure;
import com.voltcanary.aiagent.service.PrimaryExecutionProfile;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FirstPassDiagnosticTest {
    @Test void verifiedCandidateIsRecompiledAfterOneRecoveryAndOnlyThenAttached() throws Exception {
        var f = new IntentScenarioSupport();
        var preparations = new AtomicInteger();
        var repairs = new AtomicInteger();
        var models = new OrchestrationPorts.Models() {
            public AssistantProtocol.PlannerResponse plan(IntentPlans.ModelInput input, OrchestrationPorts.Session session, UUID callId) {
                return f.plan(input, List.of(new AssistantProtocol.StepProposal("dashboard", "CREATE_DASHBOARD", List.of(), List.of(), List.of("g"))));
            }
            public AssistantProtocol.StepResponse step(IntentPlans.ModelInput input, OrchestrationPorts.Session session, UUID callId) {
                return proposal("Rejected");
            }
            public AssistantProtocol.StepResponse repair(IntentPlans.ModelInput input, OrchestrationPorts.Session session, UUID callId,
                    AssistantProtocol.Diagnostic diagnostic, AssistantProtocol.Command command) {
                assertEquals(1, repairs.incrementAndGet());
                assertEquals("INTENT_OUTCOME_REJECTED", diagnostic.code());
                assertEquals("Rejected", command.payload().path("name").asText());
                assertTrue(input.state().reviews().isEmpty());
                return proposal("Corrected");
            }
            private AssistantProtocol.StepResponse proposal(String name) {
                return f.prepared("CREATE_DASHBOARD", f.mapper.createObjectNode().put("name", name).put("deviceId", UUID.randomUUID().toString()));
            }
        };
        var runtime = new IntentOrchestration(f.store, f.graph, models, (input, skill, command, session) -> {
            if (preparations.incrementAndGet() == 1)
                throw new StepPreparationFailure(new AssistantProtocol.Diagnostic("OUTCOME", "INTENT_OUTCOME_REJECTED",
                        "Fixture verified mismatch", List.of(), true));
            assertEquals("Corrected", command.payload().path("name").asText());
            return f.compile(input, skill, command, session);
        }, f.receipts, new IntentOrchestration.Runtime(f.mapper, f.validator, f.clock, Duration.ofMinutes(30), f.traces,
                new com.voltcanary.aiagent.adapter.langgraph4j.LangGraphAgentRunner()));
        var result = f.send(runtime, null, "Подготовь дашборд");
        assertEquals("WAITING_CONFIRMATION", result.status());
        assertEquals(1, result.reviews().size());
        assertEquals(2, preparations.get());
        assertEquals(1, repairs.get());
        assertTrue(f.traceEvents.contains("PREPARATION:FAILED"));
        assertTrue(f.mutations.isEmpty());
    }

    @Test void rejectedDomainCandidateFailsWithoutRepairQuestionOrMutation() throws Exception {
        var f = new IntentScenarioSupport();
        var calls = new AtomicInteger();
        var models = new OrchestrationPorts.Models() {
            public boolean automaticCorrections() { return PrimaryExecutionProfile.FIRST_PASS_DIAGNOSTIC.automaticCorrections(); }
            public AssistantProtocol.PlannerResponse plan(IntentPlans.ModelInput input, OrchestrationPorts.Session session, UUID callId) {
                calls.incrementAndGet();
                return f.plan(input, List.of(new AssistantProtocol.StepProposal("dashboard", "CREATE_DASHBOARD", List.of(), List.of(), List.of("g"))));
            }
            public AssistantProtocol.StepResponse step(IntentPlans.ModelInput input, OrchestrationPorts.Session session, UUID callId) {
                calls.incrementAndGet();
                return f.prepared("CREATE_DASHBOARD", f.mapper.createObjectNode().put("name", "Fixture").put("deviceId", UUID.randomUUID().toString()));
            }
            public AssistantProtocol.StepResponse repair(IntentPlans.ModelInput input, OrchestrationPorts.Session session, UUID callId,
                    AssistantProtocol.Diagnostic diagnostic, AssistantProtocol.Command command) {
                fail("diagnostic preparation failure must never call recovery");
                return null;
            }
        };
        var runtime = new IntentOrchestration(f.store, f.graph, models, (input, skill, command, session) -> {
            throw new StepPreparationFailure(new AssistantProtocol.Diagnostic("INVALID_PLAN", "DOMAIN_PREPARATION_REJECTED", "Fixture domain rejection", List.of(), true));
        }, f.receipts, new IntentOrchestration.Runtime(f.mapper, f.validator, f.clock, Duration.ofMinutes(30), f.traces,
                new com.voltcanary.aiagent.adapter.langgraph4j.LangGraphAgentRunner()));
        var result = f.send(runtime, null, "Подготовь дашборд");
        assertEquals("FAILED", result.status());
        assertEquals("DOMAIN_PREPARATION_REJECTED", result.step("dashboard").diagnostic().code());
        assertNull(result.pendingQuestion());
        assertEquals(2, calls.get(), "planner and step are both necessary first-pass calls");
        assertTrue(f.mutations.isEmpty());
        assertFalse(PrimaryExecutionProfile.FIRST_PASS_DIAGNOSTIC.modelJudge());
    }
}
