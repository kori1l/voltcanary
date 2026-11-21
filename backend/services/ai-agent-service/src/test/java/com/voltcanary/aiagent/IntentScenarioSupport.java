package com.voltcanary.aiagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.aiagent.adapter.ExistingOrchestrationReceipts;
import com.voltcanary.aiagent.adapter.JdbcAttemptLedger;
import com.voltcanary.aiagent.adapter.JdbcIntentPlanStore;
import com.voltcanary.aiagent.config.AiTaskProperties;
import com.voltcanary.aiagent.dto.ActionExecutionResponse;
import com.voltcanary.aiagent.dto.ExecutePlanResponse;
import com.voltcanary.aiagent.model.AgentAction;
import com.voltcanary.aiagent.model.AgentPlan;
import com.voltcanary.aiagent.model.AgentTool;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol;
import com.voltcanary.aiagent.orchestration.contract.ProtocolValidator;
import com.voltcanary.aiagent.orchestration.contract.SkillRegistry;
import com.voltcanary.aiagent.orchestration.plan.IntentOrchestration;
import com.voltcanary.aiagent.orchestration.plan.IntentPlanActions;
import com.voltcanary.aiagent.orchestration.plan.IntentPlanGraph;
import com.voltcanary.aiagent.orchestration.plan.IntentPlans;
import com.voltcanary.aiagent.orchestration.plan.OrchestrationPorts;
import com.voltcanary.aiagent.port.AssistantTraceRecorder;
import com.voltcanary.aiagent.security.AuthenticatedUser;
import com.voltcanary.aiagent.service.AiAgentOrchestrator;
import com.voltcanary.aiagent.service.AuthorizedPlanningContext;
import com.voltcanary.aiagent.service.PlanStore;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Fast fixtures use the actual migration/store/review adapter; only provider and domain transport are simulated. */
final class IntentScenarioSupport {
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final ProtocolValidator validator = new ProtocolValidator(mapper);
    final Instant now = Instant.now();
    final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    final JdbcTemplate jdbc;
    final TransactionTemplate transactions;
    final UUID owner = UUID.randomUUID();
    final UUID conversation = UUID.randomUUID();
    final OrchestrationPorts.Session session = new OrchestrationPorts.Session(new AuthenticatedUser(owner, "qa@voltcanary.test", "USER"),
            new AuthorizedPlanningContext.Scope(owner, owner), "fixture-session");
    final List<String> traceEvents = new ArrayList<>();
    final AssistantTraceRecorder traces = (c, a, s, status, code, duration, details) -> traceEvents.add(s + ":" + status);
    final Map<AgentTool, AtomicInteger> mutations = new HashMap<>();
    final Map<AgentTool, List<String>> outcomes = new HashMap<>();
    final Set<String> deletedResources = new java.util.HashSet<>();
    final PlanStore reviews;
    final AuthorizedPlanningContext contexts;
    final ExistingOrchestrationReceipts receipts;
    final SkillRegistry registry;
    final IntentPlanGraph graph;
    JdbcIntentPlanStore store;
    final IntentOrchestration.Skills skills = new IntentOrchestration.Skills(Set.of("CREATE_DEVICE", "CONNECT_SOURCE", "CREATE_DASHBOARD"), Set.of());
    final com.voltcanary.aiagent.orchestration.plan.AgentGraphRunner runner = new com.voltcanary.aiagent.adapter.langgraph4j.LangGraphAgentRunner();

    IntentScenarioSupport() throws Exception {
        this(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", ""));
    }

    IntentScenarioSupport(DriverManagerDataSource ds) throws Exception {
        jdbc = new JdbcTemplate(ds);
        if (ds.getUrl().startsWith("jdbc:h2:")) jdbc.execute("create domain timestamptz as timestamp with time zone");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V002__durable_reviewed_plans.sql"),
                new ClassPathResource("db/migration/V004__assistant_conversations.sql"), new ClassPathResource("db/migration/V005__assistant_message_provenance.sql"),
                new ClassPathResource("db/migration/V007__assistant_model_attempt_budgets.sql"), new ClassPathResource("db/migration/V008__assistant_intent_plans.sql"),
                new ClassPathResource("db/migration/V009__assistant_context_cursors.sql")).execute(ds);
        jdbc.update("insert into ai_conversations(id,user_id,account_id,title,revision,created_at,updated_at,expires_at) values(?,?,?,'QA',0,?,?,?)",
                conversation, owner, owner, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now.plusSeconds(3600)));
        transactions = new TransactionTemplate(new DataSourceTransactionManager(ds));
        restart();
        reviews = new PlanStore(jdbc, mapper, new AiTaskProperties());
        contexts = mock(AuthorizedPlanningContext.class);
        when(contexts.scope(session.user(), session.authorization())).thenReturn(session.owner());
        doAnswer(call -> {
            List<AuthorizedPlanningContext.ResourceRef> refs = call.getArgument(0);
            if (refs.stream().anyMatch(ref -> deletedResources.contains(ref.resourceId()))) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "fixture deleted resource");
            return null;
        }).when(contexts).revalidate(any(), anyString());
        var execution = mock(AiAgentOrchestrator.class);
        doAnswer(call -> {
            UUID id = call.getArgument(1);
            var saved = reviews.find(id, session.owner());
            var action = saved.plan().actions().getFirst();
            boolean reconciliation = saved.status().equals("UNKNOWN");
            if (reconciliation) reviews.claimReconciliation(saved, session.owner());
            else {
                if (!reviews.claim(saved, session.owner(), call.getArgument(4), call.getArgument(5))) return saved.execution();
                mutations.computeIfAbsent(action.tool(), key -> new AtomicInteger()).incrementAndGet();
            }
            String outcome = reconciliation ? "SUCCESS" : outcomes.getOrDefault(action.tool(), List.of()).isEmpty() ? "SUCCESS" : outcomes.get(action.tool()).removeFirst();
            Map<String, Object> result = new HashMap<>();
            String key = action.tool() == AgentTool.CREATE_DEVICE ? "deviceId" : action.tool() == AgentTool.CREATE_DATA_SOURCE ? "sourceId" : "dashboardId";
            if (outcome.equals("SUCCESS")) {
                result.put(key, UUID.nameUUIDFromBytes(id.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString());
                if (action.parameters().containsKey("deviceId")) result.put("deviceId", action.parameters().get("deviceId"));
                if (action.parameters().containsKey("name")) result.put("name", action.parameters().get("name"));
            }
            var receipt = new ActionExecutionResponse(action.id(), action.tool().name(), outcome, result, null);
            reviews.actionStarted(saved, session.owner(), action);
            reviews.actionFinished(saved, session.owner(), receipt);
            var response = new ExecutePlanResponse(id, outcome, List.of(receipt));
            reviews.finish(saved, session.owner(), response);
            return response;
        }).when(execution).execute(any(), any(), anyBoolean(), anyString(), anyInt(), anyString());
        receipts = new ExistingOrchestrationReceipts(reviews, execution, contexts, mapper);
        registry = new SkillRegistry(validator, List.of(pkg("CREATE_DEVICE", List.of(rule("name", "string", true)), "deviceId", "device-id"),
                pkg("CONNECT_SOURCE", List.of(rule("deviceId", "device-id", true)), "sourceId", "source-id"),
                pkg("CREATE_DASHBOARD", List.of(rule("deviceId", "device-id", true), rule("deviceIds", "array", false),
                        rule("sourceId", "source-id", false), rule("name", "string", true)), "dashboardId", "dashboard-id")));
        graph = new IntentPlanGraph(registry, validator, mapper);
    }

    void restart() { store = new JdbcIntentPlanStore(jdbc, transactions, mapper, validator, clock, Duration.ofSeconds(30)); }
    IntentPlanActions actions() { return new IntentPlanActions(store, receipts, mapper, validator, traces, ledger(), runner); }
    JdbcAttemptLedger ledger() { return new JdbcAttemptLedger(jdbc, transactions, mapper, clock); }
    IntentOrchestration orchestration(OrchestrationPorts.Models models) {
        return new IntentOrchestration(store, graph, models, this::compile, receipts,
                new IntentOrchestration.Runtime(mapper, validator, clock, Duration.ofMinutes(30), traces,
                        new com.voltcanary.aiagent.adapter.langgraph4j.LangGraphAgentRunner()));
    }
    OrchestrationPorts.Prepared compile(IntentPlans.ModelInput input, AssistantProtocol.Manifest skill, AssistantProtocol.Command command, OrchestrationPorts.Session checked) {
        UUID id = UUID.randomUUID();
        AgentTool tool = switch (skill.intentId()) {
            case "CREATE_DEVICE" -> AgentTool.CREATE_DEVICE;
            case "CONNECT_SOURCE" -> AgentTool.CREATE_DATA_SOURCE;
            default -> AgentTool.CREATE_DASHBOARD;
        };
        Map<String, Object> parameters = mapper.convertValue(command.payload(), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
        var action = new AgentAction(UUID.randomUUID(), tool, skill.intentId(), "fixture reviewed command", true, parameters);
        var plan = new AgentPlan(id, owner, input.currentMessage().text(), "fixture", "fixture", List.of(), List.of(action), now);
        var saved = reviews.save(plan, new AuthorizedPlanningContext.Prepared(session.owner(), Map.of(), List.of()));
        var review = new IntentPlans.Review(id, saved.version(), saved.digest(), List.of(action.id()), true);
        String key = skill.outputs().getFirst().key();
        String kind = key.equals("deviceId") ? "device" : key.equals("sourceId") ? "source" : "dashboard";
        return new OrchestrationPorts.Prepared(review, List.of(new IntentPlans.Output(key, skill.outputs().getFirst().type(), id, action.id(), "/" + key, kind)));
    }
    AssistantProtocol.TurnSubmit submit(String text, long revision, String question) {
        return new AssistantProtocol.TurnSubmit("assistant-v2", "turn.submit", UUID.randomUUID().toString(), revision, text, null, List.of(),
                new AssistantProtocol.RoutingPreference("AUTO", "policy", null), question);
    }
    IntentPlans.State send(IntentOrchestration runtime, IntentPlans.State state, String message) {
        return runtime.submit(session, conversation, state == null ? null : state.planId(), submit(message, state == null ? 0 : state.revision(),
                state == null || state.pendingQuestion() == null ? null : state.pendingQuestion().questionId()), "policy", "1", skills);
    }
    IntentPlans.State confirm(IntentPlans.State state, String step) {
        var review = state.reviews().get(step);
        return actions().confirm(session, state.planId(), new AssistantProtocol.ConfirmationRequest("assistant-v2", "confirmation.request", UUID.randomUUID().toString(),
                step, review.reviewId().toString(), state.revision(), (long) review.version(), review.digest()));
    }
    AssistantProtocol.PlannerResponse plan(IntentPlans.ModelInput input, List<AssistantProtocol.StepProposal> steps) {
        var goals = steps.stream().map(step -> new AssistantProtocol.GoalProposal(step.goalIds().getFirst(), input.currentMessage().messageId().toString(), step.intentId())).toList();
        return new AssistantProtocol.PlannerResponse("assistant-v2", "planner.response", "PLAN", "Зависимый план", null, List.of(), null, steps, goals, List.of());
    }
    AssistantProtocol.PlannerResponse continued(String step) {
        return new AssistantProtocol.PlannerResponse("assistant-v2", "planner.response", "CONTINUE", "Продолжаем сохранённый шаг", step, List.of(), null, List.of(), List.of(), List.of());
    }
    AssistantProtocol.StepResponsePrepared prepared(String intent, JsonNode payload) {
        var skill = registry.manifest(intent, skills.authorized(), Set.of());
        return new AssistantProtocol.StepResponsePrepared("assistant-v2", "step.response", "PREPARED", "Проверьте конкретные действия",
                new AssistantProtocol.Command(skill.commandSchemaId(), skill.skillVersion(), payload));
    }
    private SkillRegistry.Package pkg(String intent, List<AssistantProtocol.ManifestParameterRulesItem> rules, String output, String type) throws Exception {
        String instructions = "Interpret actual dialogue; propose typed parameters; never execute.";
        var skill = new AssistantProtocol.Manifest("assistant-v2", "skill.manifest", intent.toLowerCase(java.util.Locale.ROOT), "1", intent, intent,
                List.of("assistant-v2"), intent + ":input", intent + ":output", intent + ":command", rules,
                List.of(new AssistantProtocol.ManifestOutputsItem(output, type)), "MODEL", "REVIEW_REQUIRED", List.of(), List.of("fixture.prepare"), List.of("RECEIPT"),
                "economical", 100_000L, new AssistantProtocol.ManifestInstructions(instructions, SkillRegistry.digest(instructions)));
        var schema = mapper.createObjectNode().put("type", "object").put("additionalProperties", false);
        var fields = schema.putObject("properties");
        var required = schema.putArray("required");
        for (var rule : rules) {
            var field = fields.putObject(rule.field());
            if (rule.type().equals("array")) {
                field.put("type", "array").putObject("items").put("type", "string");
            } else {
                field.put("type", "string").put("minLength", 1);
            }
            if (rule.required()) required.add(rule.field());
        }
        return new SkillRegistry.Package(mapper.valueToTree(skill), Map.of(skill.inputSchemaId(), schema, skill.outputSchemaId(), validator.projection("stepResponse"), skill.commandSchemaId(), schema), Set.of(), Map.of());
    }
    private static AssistantProtocol.ManifestParameterRulesItem rule(String field, String type, boolean required) {
        return new AssistantProtocol.ManifestParameterRulesItem(field, type, required, false, field, 255L, List.of(), "MODEL_MAY_PROPOSE");
    }
}
