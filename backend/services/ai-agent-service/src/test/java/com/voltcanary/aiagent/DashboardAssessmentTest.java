package com.voltcanary.aiagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol;
import com.voltcanary.aiagent.orchestration.context.BoundedSkillRequests;
import com.voltcanary.aiagent.orchestration.context.ContextLoading;
import com.voltcanary.aiagent.orchestration.plan.IntentOrchestration;
import com.voltcanary.aiagent.orchestration.plan.IntentPlans;
import com.voltcanary.aiagent.orchestration.plan.OrchestrationPorts;
import com.voltcanary.aiagent.orchestration.plan.RoutedIntentModels;
import com.voltcanary.aiagent.orchestration.routing.BudgetedModelCalls;
import com.voltcanary.aiagent.orchestration.routing.ModelRouting;
import com.voltcanary.aiagent.orchestration.routing.RoutedModelCalls;
import com.voltcanary.aiagent.service.DashboardAssessment;
import com.voltcanary.aiagent.model.AgentPlan;
import com.voltcanary.aiagent.model.AgentAction;
import com.voltcanary.aiagent.model.AgentTool;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DashboardAssessmentTest {
    @Test void assessmentUsesSharedLedgerAndRejectedOrInventedEvidenceCannotSaveAReview() throws Exception {
        for (String verdict : List.of("SATISFIED", "REJECTED", "UNCERTAIN", "FORGED_SOURCE", "INVALID_JSON")) {
            var f = new IntentScenarioSupport();
            var calls = new AtomicInteger();
            var ledger = f.ledger();
            var limits = new AssistantProtocol.Limits(2L, 1_000_000L, 10_000L, 60_000L, 100_000L, "USD");
            var candidate = new AssistantProtocol.Candidate("fixture", "pinned", null, 1_000_000L, 900_000L, 1000L, "JSON_TEXT", null);
            var policy = new AssistantProtocol.RoutingPolicy("assistant-v2", "routing.policy", "policy", "1", "PINNED", "pinned",
                    List.of(new AssistantProtocol.RoutingPolicyProfilesItem("economical", List.of("CREATE_DASHBOARD"), List.of("STEP", "VERIFY"), List.of(candidate), false)),
                    limits, limits, "RESERVED_UPPER_BOUND", 500L, BigDecimal.ONE, null);
            var history = new com.voltcanary.aiagent.adapter.JdbcContextHistory(f.jdbc, f.clock);
            var requests = new BoundedSkillRequests(f.mapper, f.validator, f.registry, f.store, history,
                    new ContextLoading(f.registry, f.validator, new com.voltcanary.aiagent.adapter.JdbcContextCursors(f.jdbc), List.of(), f.clock),
                    (input, session) -> f.skills, f.receipts, (input, session) -> null, f.clock, 100_000);
            var adapter = new RoutedModelCalls.WireAdapter() {
                public String serialize(JsonNode request, JsonNode schema, String mode) { return request.toString(); }
                public BudgetedModelCalls.Reply send(String body, ModelRouting.Route route, long timeout) throws Exception {
                    int n = calls.incrementAndGet();
                    var request = f.mapper.readTree(body);
                    assertEquals("pinned", route.candidate().modelConfigId());
                    if (n == 1) return new BudgetedModelCalls.Reply("JSON_TEXT", f.mapper.writeValueAsString(f.prepared("CREATE_DASHBOARD",
                            f.mapper.createObjectNode().put("name", "Design").put("deviceId", UUID.randomUUID().toString()))), null);
                    assertEquals("verification.candidate", request.path("loadResults").get(0).path("loaderId").asText());
                    if (verdict.equals("INVALID_JSON")) return new BudgetedModelCalls.Reply("JSON_TEXT", "broken", null);
                    var data = request.path("loadResults").get(0).path("data");
                    String resolved = verdict.equals("FORGED_SOURCE") ? "SATISFIED" : verdict;
                    var response = new AssistantProtocol.AssessmentResponse("assistant-v2", "assessment.response", data.path("candidateDigest").asText(), resolved,
                            List.of(new AssistantProtocol.AssessmentCheck(request.path("messageId").asText(),
                                    verdict.equals("FORGED_SOURCE") ? "invented requirement" : request.path("message").asText(), "Explicit request coverage", resolved,
                                    List.of("actual"), "Candidate configuration evidence")));
                    return new BudgetedModelCalls.Reply("JSON_TEXT", f.mapper.writeValueAsString(response), null);
                }
            };
            var availability = new ModelRouting.Availability() {
                public boolean authorized(String provider, String model) { return true; }
                public boolean qualified(String id, String model, String profile, String intent, String phase) { return true; }
                public Set<String> outputModes(String model) { return Set.of("JSON_TEXT"); }
                public AssistantProtocol.PriceSnapshot price(String id) { return null; }
            };
            var routed = new RoutedIntentModels(new RoutedModelCalls(f.mapper, f.validator, f.registry, ledger, f.traces, f.clock), requests,
                    (input, session, phase) -> new RoutedIntentModels.Route(f.mapper.valueToTree(policy),
                            new RoutedModelCalls.Selection("economical", phase, null, 1000, 64), f.skills.authorized(), Set.of(), availability, adapter), f.store, f.validator, false, f.runner);
            var assessment = new DashboardAssessment(f.mapper, routed, f.traces);
            var models = new OrchestrationPorts.Models() {
                public boolean automaticCorrections() { return false; }
                public AssistantProtocol.PlannerResponse plan(IntentPlans.ModelInput input, OrchestrationPorts.Session session, UUID call) {
                    return f.plan(input, List.of(new AssistantProtocol.StepProposal("dashboard", "CREATE_DASHBOARD", List.of(), List.of(), List.of("g"))));
                }
                public AssistantProtocol.StepResponse step(IntentPlans.ModelInput input, OrchestrationPorts.Session session, UUID call) { return routed.step(input, session, call); }
            };
            var runtime = new IntentOrchestration(f.store, f.graph, models, (input, skill, command, session) -> {
                var plan = new AgentPlan(UUID.randomUUID(), f.owner, input.currentMessage().text(), "writer prose", "writer self-rating", List.of(),
                        List.of(new AgentAction(UUID.randomUUID(), AgentTool.CREATE_DASHBOARD, "Design", "Design", true,
                                Map.of("definition", Map.of("widgets", List.of(Map.of("id", "actual")))))), f.now);
                assessment.verify(input, session, plan);
                return f.compile(input, skill, command, session);
            }, f.receipts, new IntentOrchestration.Runtime(f.mapper, f.validator, f.clock, Duration.ofMinutes(30), f.traces,
                    new com.voltcanary.aiagent.adapter.langgraph4j.LangGraphAgentRunner()));
            var request = f.submit("Design useful information", 0, null);
            // The fixture server policy explicitly pins the same model for both calls.
            request = new AssistantProtocol.TurnSubmit(request.version(), request.kind(), request.requestId(), request.expectedRevision(), request.message(), null,
                    List.of(), new AssistantProtocol.RoutingPreference("PINNED", "policy", "pinned"), null);
            var state = runtime.submit(f.session, f.conversation, null, request, "policy", "1", f.skills);
            boolean passed = verdict.equals("SATISFIED");
            assertEquals(passed ? "WAITING_CONFIRMATION" : "FAILED", state.status(), verdict + f.mapper.valueToTree(state).toString() + f.traceEvents.toString());
            assertEquals(2, calls.get(), "no verifier format retry or alternate model");
            assertEquals(2, f.jdbc.queryForObject("select calls from ai_model_budget_plans where plan_id=?", Integer.class, state.planId()));
            assertEquals(1, f.jdbc.queryForObject("select count(*) from ai_model_budget_attempts where attempt_json like '%OUTCOME_ASSESSMENT%'", Integer.class));
            assertEquals(passed ? 1 : 0, f.jdbc.queryForObject("select count(*) from ai_reviewed_plans", Integer.class));
            assertTrue(f.mutations.isEmpty());
        }
    }
}
