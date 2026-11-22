package com.voltcanary.aiagent;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.voltcanary.aiagent.adapter.PrimaryOrchestrationJournal;
import com.voltcanary.aiagent.config.AiAgentProperties;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol;
import com.voltcanary.aiagent.orchestration.plan.IntentPlans;
import com.voltcanary.aiagent.orchestration.plan.OrchestrationPorts;
import com.voltcanary.aiagent.service.AuthorizedPlanningContext;
import com.voltcanary.aiagent.service.DashboardPlanningContext;
import com.voltcanary.aiagent.service.PrimaryDomainPreparation;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class PrimaryWorkflowContextTest {

    @Test
    void dashboardUsesTheTwoDevicesBoundByThePlanner() throws Exception {
        var f = new IntentScenarioSupport();
        new ResourceDatabasePopulator(
            new ClassPathResource("db/migration/V010__assistant_primary_bindings.sql")
        ).execute(f.jdbc.getDataSource());
        var dashboards = mock(DashboardPlanningContext.class);
        var domain = new PrimaryDomainPreparation(
            f.mapper,
            f.validator,
            f.store,
            f.reviews,
            f.contexts,
            dashboards,
            new AiAgentProperties(),
            com.voltcanary.common.web.JacksonRestClients.builder(),
            mock(PrimaryOrchestrationJournal.class),
            f.jdbc
        );
        var deviceIds = List.of(UUID.randomUUID().toString(), UUID.randomUUID().toString());
        when(dashboards.enrich(any(), any(), eq(f.session.authorization()))).thenAnswer(call -> {
            java.util.Map<?, ?> values = call.getArgument(1);
            assertEquals(deviceIds, values.get("deviceIds"));
            return call.getArgument(0);
        });
        var models = new OrchestrationPorts.Models() {
            public AssistantProtocol.PlannerResponse plan(
                IntentPlans.ModelInput input,
                OrchestrationPorts.Session session,
                UUID id
            ) {
                var devices = new AssistantProtocol.Input(
                    "deviceIds",
                    new AssistantProtocol.BindingLiteral("literal", f.mapper.valueToTree(deviceIds))
                );
                return f.plan(
                    input,
                    List.of(
                        new AssistantProtocol.StepProposal(
                            "dashboard",
                            "CREATE_DASHBOARD",
                            List.of(),
                            List.of(devices),
                            List.of("g")
                        )
                    )
                );
            }

            public AssistantProtocol.StepResponse step(
                IntentPlans.ModelInput input,
                OrchestrationPorts.Session session,
                UUID id
            ) {
                var args = f.mapper.createObjectNode();
                args.putArray("deviceIds").add(UUID.randomUUID().toString());
                domain.load(input, session, args, null);
                assertNotNull(domain.read(input, session));
                return f.prepared(
                    "CREATE_DASHBOARD",
                    f.mapper
                        .createObjectNode()
                        .put("name", "Overview")
                        .put("deviceId", deviceIds.getFirst())
                );
            }
        };
        assertEquals(
            "WAITING_CONFIRMATION",
            f.send(f.orchestration(models), null, "Create a dashboard for both devices").status()
        );
        verify(dashboards).enrich(any(), any(), eq(f.session.authorization()));
    }

    @Test
    void creationIgnoresAmbientDashboardReviewAndRefinementRequiresAnActualBase() throws Exception {
        var f = new IntentScenarioSupport();
        new ResourceDatabasePopulator(
            new ClassPathResource("db/migration/V010__assistant_primary_bindings.sql")
        ).execute(f.jdbc.getDataSource());
        var dashboards = mock(DashboardPlanningContext.class);
        when(dashboards.enrich(any(), any(), eq(f.session.authorization()))).thenAnswer(call -> {
            java.util.Map<?, ?> values = call.getArgument(1);
            assertFalse(values.containsKey("previousDashboardIntent"));
            assertFalse(values.containsKey("name"));
            return call.getArgument(0);
        });
        var domain = new PrimaryDomainPreparation(
            f.mapper,
            f.validator,
            f.store,
            f.reviews,
            f.contexts,
            dashboards,
            new AiAgentProperties(),
            com.voltcanary.common.web.JacksonRestClients.builder(),
            mock(PrimaryOrchestrationJournal.class),
            f.jdbc
        );
        var oldAction = new com.voltcanary.aiagent.model.AgentAction(
            UUID.randomUUID(),
            com.voltcanary.aiagent.model.AgentTool.CREATE_DASHBOARD,
            "Old",
            "Old",
            true,
            java.util.Map.of(
                "prepareIntent",
                java.util.Map.of("name", "Old", "widgets", List.of()),
                "definition",
                java.util.Map.of("name", "Accepted overview")
            )
        );
        var saved = f.reviews.save(
            new com.voltcanary.aiagent.model.AgentPlan(
                UUID.randomUUID(),
                f.owner,
                "Old",
                "Old",
                "Old",
                List.of(),
                List.of(oldAction),
                f.now
            ),
            new AuthorizedPlanningContext.Prepared(f.session.owner(), java.util.Map.of(), List.of())
        );
        var models = new OrchestrationPorts.Models() {
            public AssistantProtocol.PlannerResponse plan(
                IntentPlans.ModelInput input,
                OrchestrationPorts.Session session,
                UUID id
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
                IntentPlans.ModelInput input,
                OrchestrationPorts.Session session,
                UUID id
            ) {
                var state = input.state();
                var focus = new AssistantProtocol.Focus(
                    null,
                    null,
                    saved.plan().id().toString(),
                    "dashboard",
                    "ACTIVE_PLAN"
                );
                var ambient = new IntentPlans.ModelInput(
                    input.token(),
                    state.revise(
                        state.revision(),
                        state.status(),
                        state.steps(),
                        state.goals(),
                        state.facts(),
                        null,
                        focus,
                        state.skills(),
                        state.reviews(),
                        state.outputs()
                    ),
                    input.resolvedInputs(),
                    input.currentMessage()
                );
                var subject = domain.plannerChoices(ambient, session).getLast();
                assertEquals("Accepted overview", subject.label());
                assertEquals("DASHBOARD", subject.properties().path("resourceType").asText());
                assertEquals(
                    "REFINE_DASHBOARD_REVIEW",
                    subject.properties().path("requiredIntent").asText()
                );
                assertFalse(
                    domain
                        .read(ambient, session)
                        .data()
                        .path("context")
                        .has("previousDashboardIntent")
                );
                for (String intent : List.of("REFINE_DASHBOARD_REVIEW", "UPDATE_DASHBOARD_DRAFT")) {
                    var old = state.step("dashboard");
                    var step = new AssistantProtocol.StepState(
                        intent,
                        intent,
                        "2",
                        old.schemaVersion(),
                        old.revision(),
                        old.dependsOn(),
                        old.inputs(),
                        old.goalIds(),
                        old.status(),
                        old.reviewId(),
                        old.receiptId(),
                        old.diagnostic()
                    );
                    var withoutBase = new IntentPlans.ModelInput(
                        input.token(),
                        state.revise(
                            state.revision(),
                            state.status(),
                            List.of(step),
                            state.goals(),
                            state.facts(),
                            null,
                            state.focus(),
                            state.skills(),
                            state.reviews(),
                            state.outputs()
                        ),
                        input.resolvedInputs(),
                        input.currentMessage()
                    );
                    String expected = intent.equals("REFINE_DASHBOARD_REVIEW")
                        ? "UNEXECUTED_DASHBOARD_REVIEW_REQUIRED"
                        : "EXECUTED_DASHBOARD_BASE_REQUIRED";
                    assertEquals(
                        expected,
                        assertThrows(IntentPlans.Rejected.class, () ->
                            domain.read(withoutBase, session)
                        ).getMessage()
                    );
                }
                return f.prepared(
                    "CREATE_DASHBOARD",
                    f.mapper
                        .createObjectNode()
                        .put("name", "New")
                        .put("deviceId", UUID.randomUUID().toString())
                );
            }
        };
        var state = f.send(f.orchestration(models), null, "Создай другой новый дашборд");
        assertEquals(
            "WAITING_CONFIRMATION",
            state.status(),
            f.mapper.valueToTree(state).toString()
        );
        verify(dashboards).enrich(any(), any(), eq(f.session.authorization()));
    }

    @Test
    void resolvedInitialNameReachesTheExistingDashboardContextInsteadOfAnEmptyBase() throws Exception {
        var f = new IntentScenarioSupport();
        new ResourceDatabasePopulator(
            new ClassPathResource("db/migration/V010__assistant_primary_bindings.sql")
        ).execute(f.jdbc.getDataSource());
        var dashboards = mock(DashboardPlanningContext.class);
        when(dashboards.enrich(any(), any(), eq(f.session.authorization()))).thenAnswer(call -> {
            AuthorizedPlanningContext.Prepared base = call.getArgument(0);
            assertEquals("Мой трамвай", base.context().get("name"));
            return base;
        });
        var domain = new PrimaryDomainPreparation(
            f.mapper,
            f.validator,
            f.store,
            f.reviews,
            f.contexts,
            dashboards,
            new AiAgentProperties(),
            com.voltcanary.common.web.JacksonRestClients.builder(),
            mock(PrimaryOrchestrationJournal.class),
            f.jdbc
        );
        String device = UUID.randomUUID().toString();
        var models = new OrchestrationPorts.Models() {
            public AssistantProtocol.PlannerResponse plan(
                IntentPlans.ModelInput input,
                OrchestrationPorts.Session session,
                UUID id
            ) {
                return f.plan(
                    input,
                    List.of(
                        new AssistantProtocol.StepProposal(
                            "dashboard",
                            "CREATE_DASHBOARD",
                            List.of(),
                            List.of(
                                new AssistantProtocol.Input(
                                    "name",
                                    new AssistantProtocol.BindingLiteral(
                                        "literal",
                                        f.mapper.valueToTree("Мой трамвай")
                                    )
                                ),
                                new AssistantProtocol.Input(
                                    "deviceId",
                                    new AssistantProtocol.BindingLiteral(
                                        "literal",
                                        f.mapper.valueToTree(device)
                                    )
                                )
                            ),
                            List.of("g")
                        )
                    )
                );
            }

            public AssistantProtocol.StepResponse step(
                IntentPlans.ModelInput input,
                OrchestrationPorts.Session session,
                UUID id
            ) {
                assertEquals(
                    "Мой трамвай",
                    domain.read(input, session).data().at("/context/name").asText()
                );
                assertFalse(domain.includesBaseReview(input));
                var invalid = f.mapper.createObjectNode().put("outcome", "PREPARED");
                invalid
                    .putObject("command")
                    .putObject("payload")
                    .put("protocol", "dashboard-operations-v1")
                    .putArray("operations")
                    .addObject()
                    .put("op", "show_current")
                    .put("id", "speed")
                    .put("dataRef", "unavailable")
                    .put("widgetKey", "CURRENT_SPEED");
                assertThrows(
                    com.voltcanary.aiagent.orchestration.routing.ModelProposalRejected.class,
                    () -> domain.validateProposal(input, invalid)
                );
                invalid
                    .withObject("/command/payload")
                    .putArray("operations")
                    .addObject()
                    .put("op", "rename_dashboard")
                    .put("id", "name")
                    .put("name", "Different name");
                var rejected = assertThrows(
                    com.voltcanary.aiagent.orchestration.routing.ModelProposalRejected.class,
                    () -> domain.validateProposal(input, invalid)
                );
                assertTrue(rejected.diagnostic().contains("DASHBOARD_NAME_BINDING_CHANGED"));
                return f.prepared(
                    "CREATE_DASHBOARD",
                    f.mapper.createObjectNode().put("name", "Мой трамвай").put("deviceId", device)
                );
            }
        };
        assertEquals(
            "WAITING_CONFIRMATION",
            f.send(f.orchestration(models), null, "Создай Мой трамвай").status()
        );
        verify(dashboards).enrich(any(), any(), eq(f.session.authorization()));
    }
}
