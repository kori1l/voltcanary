package com.voltcanary.aiagent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.aiagent.adapter.PrimaryOrchestrationJournal;
import com.voltcanary.aiagent.config.AiAgentProperties;
import com.voltcanary.aiagent.model.AgentAction;
import com.voltcanary.aiagent.model.AgentPlan;
import com.voltcanary.aiagent.model.AgentTool;
import com.voltcanary.aiagent.orchestration.context.ContextSources;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol;
import com.voltcanary.aiagent.orchestration.contract.ProtocolValidator;
import com.voltcanary.aiagent.orchestration.contract.SkillRegistry;
import com.voltcanary.aiagent.orchestration.plan.IntentPlanStore;
import com.voltcanary.aiagent.orchestration.plan.IntentPlans;
import com.voltcanary.aiagent.orchestration.plan.OrchestrationPorts;
import com.voltcanary.aiagent.orchestration.plan.OrchestrationPorts.Session;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

/** Typed preparation only: no natural-language parsers, provider calls, retries or domain mutations. */
public final class PrimaryDomainPreparation
    implements PrimaryDomain, OrchestrationPorts.CommandReviews
{

    private final ObjectMapper mapper;
    private final ProtocolValidator validator;
    private final IntentPlanStore plans;
    private final PlanStore reviews;
    private final AuthorizedPlanningContext authority;
    private final DashboardPlanningContext dashboards;
    private final DashboardCompositionCompiler compositions;
    private final PrimaryOrchestrationJournal journal;
    private final JdbcTemplate jdbc;
    private final RestClient devices;
    private final DashboardDraftTarget draftTarget;

    public PrimaryDomainPreparation(
        ObjectMapper mapper,
        ProtocolValidator validator,
        IntentPlanStore plans,
        PlanStore reviews,
        AuthorizedPlanningContext authority,
        DashboardPlanningContext dashboards,
        AiAgentProperties properties,
        RestClient.Builder builder,
        PrimaryOrchestrationJournal journal,
        JdbcTemplate jdbc
    ) {
        this.mapper = mapper;
        this.validator = validator;
        this.plans = plans;
        this.reviews = reviews;
        this.authority = authority;
        this.dashboards = dashboards;
        this.compositions = new DashboardCompositionCompiler(properties, builder, mapper);
        this.journal = journal;
        this.jdbc = jdbc;
        this.devices = builder.clone().baseUrl(properties.getServices().getDeviceBaseUrl()).build();
        this.draftTarget = new DashboardDraftTarget(
            builder,
            properties.getServices().getDashboardBaseUrl(),
            mapper,
            reviews
        );
    }

    @Override
    public String id() {
        return "workflow.context";
    }

    @Override
    public boolean includesBaseReview(IntentPlans.ModelInput input) {
        return !input.state().step(step(input)).intentId().equals(DashboardSkillContract.CREATE);
    }

    @Override
    public List<AssistantProtocol.Choice> plannerChoices(
        IntentPlans.ModelInput input,
        Session session
    ) {
        var selected = selectedDevices(input, session);
        var result = new ArrayList<AssistantProtocol.Choice>();
        for (String id : selected) {
            var device = PlanningContextRead.read(
                devices,
                "/devices/" + UUID.fromString(id),
                session.authorization(),
                true
            );
            var properties = mapper
                .createObjectNode()
                .put("deviceId", id)
                .put("selection", "UI_CONTEXT");
            String name = device.path("name").asText(id);
            if (name.length() > 255) throw IntentPlans.reject("RESOURCE_LABEL_EXCEEDS_LIMIT");
            result.add(new AssistantProtocol.Choice(id, "device", name, properties));
        }
        var base = base(input, session);
        if (base != null && base.plan().actions().size() == 1) {
            var action = base.plan().actions().getFirst();
            if (
                List.of(AgentTool.CREATE_DASHBOARD, AgentTool.UPDATE_DASHBOARD_DRAFT).contains(
                    action.tool()
                )
            ) {
                String dashboardName = mapper
                    .valueToTree(action.parameters())
                    .at("/definition/name")
                    .asText();
                var properties = mapper
                    .createObjectNode()
                    .put("reviewId", base.plan().id().toString())
                    .put("reviewStatus", base.status())
                    .put("resourceType", "DASHBOARD")
                    .put("name", dashboardName);
                var turn = validator.parse(
                    plans.request(
                        session.owner(),
                        input.state().planId(),
                        input.token().requestId()
                    )
                );
                properties.put(
                    "explicitBase",
                    base.plan().id().toString().equals(turn.path("selectedReviewId").asText())
                );
                if (
                    base.status().equals("SUCCESS") ||
                    action.tool() == AgentTool.UPDATE_DASHBOARD_DRAFT
                ) {
                    properties.set(
                        "draftTarget",
                        mapper.valueToTree(draftTarget.load(base, session.authorization()))
                    );
                    properties.put("requiredIntent", "UPDATE_DASHBOARD_DRAFT");
                } else properties.put("requiredIntent", DashboardSkillContract.REFINE);
                result.add(
                    new AssistantProtocol.Choice(
                        base.plan().id().toString(),
                        "dashboard-review",
                        dashboardName.isBlank() ? "Selected dashboard review" : dashboardName,
                        properties
                    )
                );
            }
        }
        return List.copyOf(result);
    }

    private List<String> selectedDevices(IntentPlans.ModelInput input, Session session) {
        // Selection is structured persisted input, never a semantic extraction from dialogue.
        var rows = jdbc.query(
            """
            select r.request_json from ai_orchestration_requests r
            join ai_orchestrations p on p.id=r.plan_id
            join ai_conversation_messages m on m.id=r.message_id
            where p.conversation_id=? and p.user_id=? and p.account_id=? and r.request_kind='TURN'
            order by m.sequence desc limit 100
            """,
            (row, index) -> row.getString(1),
            input.state().conversationId(),
            session.owner().userId(),
            session.owner().accountId()
        );
        for (String row : rows) {
            var selected = new ArrayList<String>();
            for (var item : validator.parse(row).path("selection")) {
                if (item.path("field").asText().equals("deviceId")) selected.add(
                    UUID.fromString(item.path("ref").asText()).toString()
                );
            }
            if (selected.size() > 20) throw IntentPlans.reject("SELECTED_DEVICE_LIMIT");
            if (!selected.isEmpty()) return selected.stream().distinct().toList();
        }
        if (rows.size() == 100) throw IntentPlans.reject("SELECTION_HISTORY_REQUIRES_NARROWING");
        return List.of();
    }

    @Override
    public JsonNode resultSchema() {
        return mapper.valueToTree(
            Map.of(
                "type",
                "object",
                "properties",
                Map.of(
                    "context",
                    Map.of("type", "object"),
                    "operationSchema",
                    Map.of("type", "object")
                ),
                "required",
                List.of("context", "operationSchema"),
                "additionalProperties",
                false
            )
        );
    }

    @Override
    public ContextSources.Page load(
        IntentPlans.ModelInput input,
        Session session,
        JsonNode arguments,
        ContextSources.Position position
    ) {
        if (position != null) throw IntentPlans.reject("WORKFLOW_CURSOR_UNSUPPORTED");
        var context = build(input, session, arguments);
        save(input, context);
        return page(input.state().step(step(input)).intentId(), context);
    }

    @Override
    public AssistantProtocol.LoadedContext read(IntentPlans.ModelInput input, Session session) {
        var stored = cached(input);
        var context = stored == null ? build(input, session, mapper.createObjectNode()) : stored;
        authority.revalidate(context.refs(), session.authorization());
        save(input, context);
        var page = page(input.state().step(step(input)).intentId(), context);
        return new AssistantProtocol.LoadedContext(
            id(),
            page.snapshotId(),
            page.asOf().toString(),
            "COMPLETE",
            null,
            page.data()
        );
    }

    private ContextSources.Page page(String intent, AuthorizedPlanningContext.Prepared context) {
        var data = mapper.createObjectNode();
        data.set("context", mapper.valueToTree(context.context()));
        data.set(
            "operationSchema",
            DashboardSkillContract.supports(intent)
                ? DashboardSkillContract.schema(mapper, intent, context.context())
                : mapper.createObjectNode()
        );
        return new ContextSources.Page(
            SkillRegistry.digest(data.toString()),
            Instant.now(),
            data,
            null
        );
    }

    private String step(IntentPlans.ModelInput input) {
        return input
            .state()
            .steps()
            .stream()
            .filter(item -> item.status().equals("PREPARING"))
            .findFirst()
            .orElseThrow()
            .stepId();
    }

    private AuthorizedPlanningContext.Prepared build(
        IntentPlans.ModelInput input,
        Session session,
        JsonNode arguments
    ) {
        if (
            !authority.scope(session.user(), session.authorization()).equals(session.owner())
        ) throw IntentPlans.reject("CONTEXT_SCOPE_CHANGED");
        var values = new LinkedHashMap<String, Object>();
        var turn = validator.read(
            "turnSubmit",
            validator.parse(
                plans.request(session.owner(), input.state().planId(), input.token().requestId())
            ),
            AssistantProtocol.TurnSubmit.class
        );
        var selected = new ArrayList<String>();
        for (var item : turn.selection()) {
            if (item.field().equals("deviceId")) selected.add(
                UUID.fromString(item.ref()).toString()
            );
            else if (
                List.of("locationId", "alertId", "sourceId", "name").contains(item.field())
            ) values.put(item.field(), item.ref());
        }
        input
            .resolvedInputs()
            .forEach((key, value) -> values.put(key, mapper.convertValue(value, Object.class)));
        String intent = input.state().step(step(input)).intentId();
        PlanStore.Stored base = base(input, session);
        if (intent.equals(DashboardSkillContract.CREATE) && dashboardBase(base)) {
            if (turn.selectedReviewId() != null) throw IntentPlans.reject(
                "DASHBOARD_BASE_REQUIRES_EDIT_SKILL"
            );
            base = null; // A new creation never inherits the ambient dashboard review.
        }
        if (
            base != null &&
            base.plan().actions().size() == 1 &&
            base.plan().actions().getFirst().parameters().get("prepareIntent") instanceof
                Map<?, ?> previous
        ) {
            values.put("previousDashboardIntent", previous);
            if (previous.get("name") instanceof String name) values.putIfAbsent("name", name);
            if (selected.isEmpty() && previous.get("deviceIds") instanceof List<?> ids) ids.forEach(
                id -> selected.add(id.toString())
            );
        }
        if (input.resolvedInputs().containsKey("deviceIds")) {
            selected.clear();
            input
                .resolvedInputs()
                .get("deviceIds")
                .forEach(value -> selected.add(UUID.fromString(value.asText()).toString()));
        } else if (input.resolvedInputs().containsKey("deviceId")) {
            selected.clear();
            selected.add(UUID.fromString(String.valueOf(values.get("deviceId"))).toString());
        } else if (arguments.has("deviceIds")) {
            selected.clear();
            arguments
                .path("deviceIds")
                .forEach(value -> selected.add(UUID.fromString(value.asText()).toString()));
        } else if (selected.isEmpty()) {
            selected.addAll(selectedDevices(input, session));
        }
        if (!selected.isEmpty()) values.put("deviceIds", selected.stream().distinct().toList());
        if (DashboardSkillContract.supports(intent)) {
            boolean dashboardBase = dashboardBase(base);
            if (
                intent.equals(DashboardSkillContract.CREATE) && dashboardBase
            ) throw IntentPlans.reject("DASHBOARD_BASE_REQUIRES_EDIT_SKILL");
            if (
                intent.equals(DashboardSkillContract.REFINE) &&
                (!dashboardBase ||
                    base.plan().actions().getFirst().tool() != AgentTool.CREATE_DASHBOARD ||
                    !List.of("READY", "SUPERSEDED").contains(base.status()))
            ) throw IntentPlans.reject("UNEXECUTED_DASHBOARD_REVIEW_REQUIRED");
            Map<String, Object> target = intent.equals("UPDATE_DASHBOARD_DRAFT")
                ? draftTarget.load(base, session.authorization())
                : null;
            var initial = new LinkedHashMap<String, Object>();
            if (values.get("name") instanceof String name) initial.put("name", name);
            var context = dashboards.enrich(
                new AuthorizedPlanningContext.Prepared(
                    session.owner(),
                    Map.copyOf(initial),
                    List.of()
                ),
                values,
                session.authorization()
            );
            var compact = new LinkedHashMap<>(context.context());
            DashboardOperationContext.compact(compact);
            String requestedName = DashboardTaskName.standaloneCommand(
                input.currentMessage().text()
            );
            if (requestedName != null) compact.put("requestedDashboardName", requestedName);
            compact.put("dashboardPlanningProtocol", DashboardOperationProtocol.VERSION);
            compact.put("dashboardTurnId", input.token().requestId().toString());
            if (target != null) compact.put("dashboardDraftTarget", target);
            return new AuthorizedPlanningContext.Prepared(
                session.owner(),
                Map.copyOf(compact),
                context.refs()
            );
        }
        if (selected.size() == 1) values.putIfAbsent("deviceId", selected.getFirst());
        return authority.prepare(
            session.user(),
            session.authorization(),
            input.currentMessage().text(),
            values
        );
    }

    private boolean dashboardBase(PlanStore.Stored base) {
        return (
            base != null &&
            base.plan().actions().size() == 1 &&
            List.of(AgentTool.CREATE_DASHBOARD, AgentTool.UPDATE_DASHBOARD_DRAFT).contains(
                base.plan().actions().getFirst().tool()
            ) &&
            base.plan().actions().getFirst().parameters().containsKey("prepareIntent")
        );
    }

    /** Same trusted context/schema is used for model output validation and domain preparation. */
    public void validateProposal(IntentPlans.ModelInput input, JsonNode response) {
        String intent = input.state().step(step(input)).intentId();
        String outcome = response.path("outcome").asText();
        if (
            DashboardSkillContract.supports(intent) &&
            outcome.equals("NO_CHANGE") &&
            DashboardTaskName.standaloneCommand(input.currentMessage().text()) != null
        ) {
            throw new com.voltcanary.aiagent.orchestration.routing.ModelProposalRejected(
                response,
                "DASHBOARD_NAME_BINDING_CHANGED: prepare rename_dashboard using the exact name in the current user message."
            );
        }
        if (!outcome.equals("PREPARED")) return;
        if (AgentTool.CREATE_DATA_SOURCE.name().equals(intent)) {
            try {
                sourceDevice(
                    input.resolvedInputs().get("deviceId"),
                    response.at("/command/payload/deviceId")
                );
            } catch (IntentPlans.Rejected rejected) {
                throw new com.voltcanary.aiagent.orchestration.routing.ModelProposalRejected(
                    response,
                    rejected.getMessage() +
                        ": use the deviceId supplied in contract.resolvedInputs."
                );
            }
            return;
        }
        if (!DashboardSkillContract.supports(intent)) return;
        var context = cached(input);
        if (
            context == null
        ) throw new com.voltcanary.aiagent.orchestration.routing.ModelRouting.Stop(
            "WORKFLOW_CONTEXT_MISSING"
        );
        var requestedName = context.context().get("requestedDashboardName");
        if (requestedName != null || DashboardSkillContract.CREATE.equals(intent)) {
            JsonNode name =
                requestedName != null
                    ? mapper.valueToTree(requestedName)
                    : input.resolvedInputs().get("name");
            if (name != null && name.isTextual()) {
                var operations = response.at("/command/payload/operations");
                var names = java.util.stream.StreamSupport.stream(operations.spliterator(), false)
                    .filter(operation -> "rename_dashboard".equals(operation.path("op").asText()))
                    .map(operation -> operation.path("name"))
                    .toList();
                if (!names.equals(List.of(name))) {
                    throw new com.voltcanary.aiagent.orchestration.routing.ModelProposalRejected(
                        response,
                        "DASHBOARD_NAME_BINDING_CHANGED: include exactly one rename_dashboard using the exact requested dashboard name."
                    );
                }
            }
        }
        try {
            DashboardSkillContract.validate(
                mapper,
                validator,
                intent,
                response.at("/command/payload"),
                context.context()
            );
        } catch (ProtocolValidator.RegisteredViolation invalid) {
            throw new com.voltcanary.aiagent.orchestration.routing.ModelProposalRejected(
                response,
                "Dashboard context schema: " + invalid.locations()
            );
        } catch (IllegalArgumentException invalid) {
            throw new com.voltcanary.aiagent.orchestration.routing.ModelProposalRejected(
                response,
                "Dashboard reference validation: " + invalid.getMessage()
            );
        }
    }

    private PlanStore.Stored base(IntentPlans.ModelInput input, Session session) {
        var request = validator.parse(
            plans.request(session.owner(), input.state().planId(), input.token().requestId())
        );
        String id = request.path("selectedReviewId").asText(input.state().focus().reviewId());
        if (id == null) return null;
        var value = reviews.find(UUID.fromString(id), session.owner());
        authority.revalidate(value.refs(), session.authorization());
        return value;
    }

    @Override
    public OrchestrationPorts.Prepared prepare(
        IntentPlans.ModelInput input,
        AssistantProtocol.Manifest skill,
        AssistantProtocol.Command command,
        Session session
    ) {
        return prepare(input, skill, command, session, null);
    }

    public OrchestrationPorts.Prepared prepare(
        IntentPlans.ModelInput input,
        AssistantProtocol.Manifest skill,
        AssistantProtocol.Command command,
        Session session,
        com.voltcanary.aiagent.llm.adapter.AiResolvedModel selected
    ) {
        return prepare(input, skill, command, session, selected, (candidate, context) -> {});
    }

    public OrchestrationPorts.Prepared prepare(
        IntentPlans.ModelInput input,
        AssistantProtocol.Manifest skill,
        AssistantProtocol.Command command,
        Session session,
        com.voltcanary.aiagent.llm.adapter.AiResolvedModel selected,
        java.util.function.BiConsumer<AgentPlan, AuthorizedPlanningContext.Prepared> candidateCheck
    ) {
        try {
            return prepareChecked(input, skill, command, session, selected, candidateCheck);
        } catch (DashboardPreparationRejected rejected) {
            throw new com.voltcanary.aiagent.orchestration.plan.StepPreparationFailure(
                new AssistantProtocol.Diagnostic(
                    rejected.category(),
                    rejected.code(),
                    com.voltcanary.aiagent.conversation.ConversationRedaction.text(
                        rejected.getMessage()
                    ),
                    List.of(),
                    rejected.retriable()
                )
            );
        }
    }

    private OrchestrationPorts.Prepared prepareChecked(
        IntentPlans.ModelInput input,
        AssistantProtocol.Manifest skill,
        AssistantProtocol.Command command,
        Session session,
        com.voltcanary.aiagent.llm.adapter.AiResolvedModel selected,
        java.util.function.BiConsumer<AgentPlan, AuthorizedPlanningContext.Prepared> candidateCheck
    ) {
        var context = cached(input);
        if (context == null || !context.scope().equals(session.owner())) throw IntentPlans.reject(
            "WORKFLOW_CONTEXT_MISSING"
        );
        authority.revalidate(context.refs(), session.authorization());
        var tool = skill.intentId().equals(DashboardSkillContract.REFINE)
            ? AgentTool.CREATE_DASHBOARD
            : AgentTool.valueOf(skill.intentId());
        Map<String, Object> payload = mapper.convertValue(
            command.payload(),
            new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}
        );
        new PlanningInputGuard().inspect(payload);
        if (tool == AgentTool.CREATE_DEVICE) {
            payload = new PrimaryDeviceProposal(payload, context.refs()).reviewPayload(
                (String) context.context().get("locationId")
            );
        } else if (
            tool == AgentTool.CREATE_ALERT_RULE ||
            tool == AgentTool.UPDATE_ALERT_RULE ||
            tool == AgentTool.TEST_ALERT_RULE
        ) {
            var submitted = validator.parse(
                plans.request(session.owner(), input.state().planId(), input.token().requestId())
            );
            requireLegacyRuleSourceCompatibility(submitted.path("selection"));
            if (tool == AgentTool.UPDATE_ALERT_RULE) {
                requireRuleBinding(
                    input.resolvedInputs().get("ruleId"),
                    command.payload().path("ruleId")
                );
            }
            payload = alertRulePayload(tool, payload);
        } else if (tool == AgentTool.CREATE_SIMULATOR_CONFIG) {
            String deviceId = sourceDevice(
                input.resolvedInputs().get("deviceId"),
                command.payload().path("deviceId")
            );
            context = retainSimulatorSource(
                context,
                deviceId,
                input.resolvedInputs().get("dataSourceId"),
                command.payload().path("dataSourceId")
            );
            var source = context.refs().getLast();
            var actual = PlanningContextRead.read(
                devices,
                "/devices/" + deviceId + "/data-sources/" + source.resourceId(),
                session.authorization(),
                true
            );
            if (!source.resourceId().equals(actual.path("id").asText())) throw IntentPlans.reject(
                "SIMULATOR_SOURCE_UNAVAILABLE"
            );
            payload = simulatorPayload(payload, actual.path("externalId").asText());
        }
        var action = new AgentAction(
            UUID.randomUUID(),
            tool,
            skill.intentId(),
            "Проверьте подготовленное действие",
            true,
            payload
        );
        var plan = new AgentPlan(
            UUID.randomUUID(),
            session.owner().userId(),
            input.currentMessage().text(),
            "Подготовлено через assistant-v2",
            "Проверьте результат перед подтверждением.",
            List.of("assistant-v2"),
            List.of(action),
            Instant.now()
        );
        if (selected != null) plan = new AgentPlan(
            plan.id(),
            plan.userId(),
            plan.userMessage(),
            plan.summary(),
            plan.explanation(),
            plan.safetyNotes(),
            plan.actions(),
            plan.createdAt(),
            selected.provider().getProviderType().name(),
            selected.provider().getName(),
            selected.modelConfig().getModelId(),
            selected.provider().getId(),
            selected.modelConfig().getId(),
            false
        );
        var prior = skill.intentId().equals(DashboardSkillContract.CREATE)
            ? null
            : base(input, session);
        Object pinnedDraftTarget = context.context().get("dashboardDraftTarget");
        if (tool == AgentTool.CREATE_DASHBOARD || tool == AgentTool.UPDATE_DASHBOARD_DRAFT) {
            DashboardSkillContract.validate(
                mapper,
                validator,
                skill.intentId(),
                command.payload(),
                context.context()
            );
            var domainPayload = DashboardSkillContract.domainPayload(
                mapper,
                skill.intentId(),
                command.payload()
            );
            var envelope = DashboardOperationProtocol.parse(domainPayload, mapper);
            var compiled = DashboardOperationCompiler.compile(
                envelope,
                context,
                input.token().requestId().toString()
            );
            if (compiled.restoreSteps() != null) {
                if (prior == null) throw IntentPlans.reject("UNDO_REVIEW_UNAVAILABLE");
                Map<String, Object> restore =
                    compiled.restoreSteps() == 0
                        ? Map.of("keepReview", true)
                        : Map.of("restoreSteps", compiled.restoreSteps());
                plan = replace(
                    plan,
                    new AgentAction(
                        action.id(),
                        tool,
                        action.title(),
                        action.description(),
                        true,
                        restore
                    )
                );
                var history = new DashboardReviewHistory(reviews, authority);
                CreationTaskPlanning.PreparedTask restored;
                if (tool == AgentTool.UPDATE_DASHBOARD_DRAFT) {
                    if (
                        !(pinnedDraftTarget instanceof Map<?, ?> target) ||
                        !(target.get("dashboardId") instanceof String dashboardId)
                    ) {
                        throw IntentPlans.reject("DASHBOARD_DRAFT_INVALID");
                    }
                    restored = history.restoreDraft(
                        plan,
                        prior,
                        session.authorization(),
                        draftTarget,
                        dashboardId
                    );
                } else {
                    restored = history.restore(
                        plan,
                        prior,
                        session.authorization(),
                        "v2ParentReviewId"
                    );
                }
                plan = restored.plan();
                context = restored.context();
            } else {
                var proposed = new AgentAction(
                    action.id(),
                    tool,
                    action.title(),
                    action.description(),
                    true,
                    compiled.intent()
                );
                plan = compositions.compile(plan, context, proposed, session.authorization());
                if (plan.actions().size() != 1) throw new DashboardPreparationRejected(
                    plan.explanation()
                );
                PrimaryDashboardVerification.verify(
                    mapper,
                    prior,
                    plan,
                    compiled,
                    envelope,
                    context
                );
                var metadata = new LinkedHashMap<>(plan.actions().getFirst().parameters());
                metadata.put("planningProtocol", DashboardOperationProtocol.VERSION);
                metadata.put("operationChanges", compiled.allowedChanges());
                metadata.put("expectedOutcomes", compiled.expectedOutcomes());
                metadata.put("responsiveReflow", compiled.responsiveReflow());
                plan = replace(
                    plan,
                    new AgentAction(
                        action.id(),
                        tool,
                        action.title(),
                        action.description(),
                        true,
                        Map.copyOf(metadata)
                    )
                );
            }
            if (tool == AgentTool.UPDATE_DASHBOARD_DRAFT) {
                var target = draftTarget.load(prior, session.authorization());
                if (!target.equals(pinnedDraftTarget)) throw IntentPlans.conflict(
                    "DASHBOARD_DRAFT_VERSION_CHANGED"
                );
                var prepared = plan.actions().getFirst();
                var values = new LinkedHashMap<>(prepared.parameters());
                values.putAll(target);
                plan = replace(
                    plan,
                    new AgentAction(
                        prepared.id(),
                        AgentTool.UPDATE_DASHBOARD_DRAFT,
                        "Обновить черновик дашборда",
                        "Будет обновлён этот дашборд после проверки его версии.",
                        true,
                        Map.copyOf(values)
                    )
                );
            }
        } else if (tool == AgentTool.CREATE_DATA_SOURCE) {
            if (
                !authority.hslSourceAvailable() ||
                !(context.context().get("deviceSetupCapabilities") instanceof List<?> installed) ||
                !installed.contains("HSL_HFP") ||
                !"HSL_HFP".equals(payload.get("provider"))
            ) throw IntentPlans.reject("SOURCE_INTEGRATION_UNAVAILABLE");
            String deviceId = sourceDevice(
                input.resolvedInputs().get("deviceId"),
                command.payload().path("deviceId")
            );
            var device = PlanningContextRead.read(
                devices,
                "/devices/" + deviceId,
                session.authorization(),
                true
            );
            if (
                !deviceId.equals(device.path("id").asText()) ||
                !"VEHICLE".equals(device.path("type").asText()) ||
                !"ACTIVE".equals(device.path("status").asText())
            ) throw IntentPlans.reject("SOURCE_DEVICE_INCOMPATIBLE");
            context = retainSourceDevice(context, deviceId);
        } else if (tool != AgentTool.CREATE_DEVICE) {
            var validated = authority.prepare(
                session.user(),
                session.authorization(),
                input.currentMessage().text(),
                payload
            );
            if (!context.scope().equals(validated.scope())) throw IntentPlans.reject(
                "CONTEXT_SCOPE_CHANGED"
            );
            var refs = new ArrayList<>(context.refs());
            validated
                .refs()
                .stream()
                .filter(ref -> List.of("device", "location", "alert", "rule").contains(ref.kind()))
                .filter(ref ->
                    refs
                        .stream()
                        .noneMatch(
                            existing ->
                                existing.kind().equals(ref.kind()) &&
                                existing.resourceId().equals(ref.resourceId())
                        )
                )
                .forEach(refs::add);
            context = new AuthorizedPlanningContext.Prepared(
                context.scope(),
                context.context(),
                List.copyOf(refs)
            );
        }
        if (DashboardSkillContract.supports(skill.intentId())) candidateCheck.accept(plan, context);
        authority.revalidate(context.refs(), session.authorization());
        if (
            tool == AgentTool.UPDATE_DASHBOARD_DRAFT &&
            !draftTarget.load(prior, session.authorization()).equals(pinnedDraftTarget)
        ) throw IntentPlans.conflict("DASHBOARD_DRAFT_VERSION_CHANGED");
        var metadata = new LinkedHashMap<>(context.context());
        if (tool == AgentTool.UPDATE_DASHBOARD_DRAFT) metadata.put(
            "dashboardDraftTarget",
            pinnedDraftTarget
        );
        if (prior != null) metadata.put("v2ParentReviewId", prior.plan().id().toString());
        var saved = reviews.save(
            plan,
            new AuthorizedPlanningContext.Prepared(
                session.owner(),
                Map.copyOf(metadata),
                context.refs()
            )
        );
        journal.bind(saved.plan().id(), input.state().planId(), step(input));
        var review = new IntentPlans.Review(
            saved.plan().id(),
            saved.version(),
            saved.digest(),
            List.of(saved.plan().actions().getFirst().id()),
            true
        );
        var outputs = skill
            .outputs()
            .stream()
            .map(output ->
                new IntentPlans.Output(
                    output.key(),
                    output.type(),
                    review.reviewId(),
                    review.actions().getFirst(),
                    "/" + output.key(),
                    output.type().equals("device-id")
                        ? "device"
                        : output.type().equals("dashboard-id")
                          ? "dashboard"
                          : output.type().equals("source-id")
                            ? "source"
                            : null
                )
            )
            .toList();
        return new OrchestrationPorts.Prepared(review, outputs);
    }

    static Map<String, Object> simulatorPayload(Map<String, Object> payload, String externalId) {
        if (externalId.isBlank()) throw IntentPlans.reject("SIMULATOR_SOURCE_UNAVAILABLE");
        Object proposed = payload.get("sourceId");
        if (proposed != null && !externalId.equals(proposed)) throw IntentPlans.reject(
            "SIMULATOR_EXTERNAL_ID_CHANGED"
        );
        var reviewed = new LinkedHashMap<>(payload);
        reviewed.values().removeIf(java.util.Objects::isNull);
        reviewed.put("sourceId", externalId);
        return Map.copyOf(reviewed);
    }

    static void requireRuleBinding(JsonNode boundRule, JsonNode proposedRule) {
        if (
            boundRule != null &&
            !UUID.fromString(boundRule.asText()).equals(UUID.fromString(proposedRule.asText()))
        ) {
            throw IntentPlans.reject("RULE_BINDING_CHANGED");
        }
    }

    static Map<String, Object> alertRulePayload(AgentTool tool, Map<String, Object> payload) {
        var result = new LinkedHashMap<>(payload);
        result.values().removeIf(java.util.Objects::isNull);
        Object deviceId = payload.get("deviceId");
        if (deviceId != null) {
            result.put("scopeType", "DEVICE");
            result.put("scopeId", deviceId);
        } else if (tool != AgentTool.UPDATE_ALERT_RULE) {
            result.put("scopeType", "FLEET");
        }
        return Map.copyOf(result);
    }

    static String sourceDevice(JsonNode boundDevice, JsonNode proposedDevice) {
        String deviceId = UUID.fromString(proposedDevice.asText()).toString();
        if (
            boundDevice != null &&
            !deviceId.equals(UUID.fromString(boundDevice.asText()).toString())
        ) {
            throw IntentPlans.reject("SOURCE_DEVICE_BINDING_CHANGED");
        }
        return deviceId;
    }

    static AuthorizedPlanningContext.Prepared retainSourceDevice(
        AuthorizedPlanningContext.Prepared context,
        String deviceId
    ) {
        var refs = new ArrayList<>(context.refs());
        if (
            refs
                .stream()
                .noneMatch(ref -> "device".equals(ref.kind()) && deviceId.equals(ref.resourceId()))
        ) {
            refs.add(
                new AuthorizedPlanningContext.ResourceRef(
                    UUID.randomUUID().toString(),
                    "device",
                    deviceId,
                    Map.of()
                )
            );
        }
        return new AuthorizedPlanningContext.Prepared(
            context.scope(),
            context.context(),
            List.copyOf(refs)
        );
    }

    static AuthorizedPlanningContext.Prepared retainSimulatorSource(
        AuthorizedPlanningContext.Prepared context,
        String deviceId,
        JsonNode boundSource,
        JsonNode proposedSource
    ) {
        String sourceId = UUID.fromString(proposedSource.asText()).toString();
        if (
            boundSource != null &&
            !sourceId.equals(UUID.fromString(boundSource.asText()).toString())
        ) {
            throw IntentPlans.reject("SIMULATOR_SOURCE_BINDING_CHANGED");
        }
        var refs = new ArrayList<>(context.refs());
        refs.add(
            new AuthorizedPlanningContext.ResourceRef(
                UUID.randomUUID().toString(),
                "source",
                sourceId,
                Map.of("deviceId", deviceId)
            )
        );
        return new AuthorizedPlanningContext.Prepared(
            context.scope(),
            context.context(),
            List.copyOf(refs)
        );
    }

    private AgentPlan replace(AgentPlan plan, AgentAction action) {
        return new AgentPlan(
            plan.id(),
            plan.userId(),
            plan.userMessage(),
            plan.summary(),
            plan.explanation(),
            plan.safetyNotes(),
            List.of(action),
            plan.createdAt(),
            plan.providerType(),
            plan.providerName(),
            plan.modelId(),
            plan.providerId(),
            plan.modelConfigId(),
            plan.fallbackUsed()
        );
    }

    static void requireLegacyRuleSourceCompatibility(JsonNode selection) {
        for (var selected : selection) {
            if ("sourceBinding".equals(selected.path("field").asText())) {
                throw new com.voltcanary.aiagent.orchestration.plan.StepPreparationFailure(
                    new AssistantProtocol.Diagnostic(
                        "RESOURCE",
                        "VCL_SOURCE_RULE_EXECUTION_UNAVAILABLE",
                        "Для выбранной привязки VCL выполнение правил недоступно. Подготовка правила для этого источника остановлена.",
                        List.of(),
                        false
                    )
                );
            }
        }
    }

    private AuthorizedPlanningContext.Prepared cached(IntentPlans.ModelInput input) {
        var rows = jdbc.query(
            "select context_json from ai_orchestration_contexts where plan_id=? and request_id=? and step_id=?",
            (row, index) -> row.getString(1),
            input.state().planId(),
            input.token().requestId(),
            step(input)
        );
        if (rows.isEmpty()) return null;
        try {
            return mapper.readValue(rows.getFirst(), AuthorizedPlanningContext.Prepared.class);
        } catch (java.io.IOException invalid) {
            throw IntentPlans.reject("WORKFLOW_CONTEXT_INVALID");
        }
    }

    private void save(IntentPlans.ModelInput input, AuthorizedPlanningContext.Prepared context) {
        String json;
        try {
            json = mapper.writeValueAsString(context);
        } catch (java.io.IOException invalid) {
            throw IntentPlans.reject("WORKFLOW_CONTEXT_INVALID");
        }
        int count = jdbc.update(
            "update ai_orchestration_contexts set context_json=? where plan_id=? and request_id=? and step_id=?",
            json,
            input.state().planId(),
            input.token().requestId(),
            step(input)
        );
        if (count == 0) jdbc.update(
            "insert into ai_orchestration_contexts(plan_id,request_id,step_id,context_json) values(?,?,?,?)",
            input.state().planId(),
            input.token().requestId(),
            step(input),
            json
        );
    }
}
