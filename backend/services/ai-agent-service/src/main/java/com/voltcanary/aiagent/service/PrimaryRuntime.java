package com.voltcanary.aiagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.aiagent.adapter.ExistingModelAvailability;
import com.voltcanary.aiagent.adapter.PrimaryModelTransport;
import com.voltcanary.aiagent.llm.adapter.AiResolvedModel;
import com.voltcanary.aiagent.llm.adapter.AiAgentModelRunRecorder;
import com.voltcanary.aiagent.model.AgentTool;
import com.voltcanary.aiagent.orchestration.context.BoundedSkillRequests;
import com.voltcanary.aiagent.orchestration.collaboration.CollaborationStore;
import com.voltcanary.aiagent.orchestration.collaboration.ConsultationWorker;
import com.voltcanary.aiagent.orchestration.collaboration.NameConsultation;
import com.voltcanary.aiagent.orchestration.collaboration.Requirements;
import com.voltcanary.aiagent.port.AssistantTraceRecorder;
import com.voltcanary.aiagent.orchestration.context.ContextLoading;
import com.voltcanary.aiagent.orchestration.context.ContextSources;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol;
import com.voltcanary.aiagent.orchestration.contract.ProtocolValidator;
import com.voltcanary.aiagent.orchestration.contract.SkillRegistry;
import com.voltcanary.aiagent.orchestration.plan.IntentOrchestration;
import com.voltcanary.aiagent.orchestration.plan.IntentPlanActions;
import com.voltcanary.aiagent.orchestration.plan.IntentPlanGraph;
import com.voltcanary.aiagent.orchestration.plan.IntentPlanStore;
import com.voltcanary.aiagent.orchestration.plan.OrchestrationPorts;
import com.voltcanary.aiagent.orchestration.plan.RoutedIntentModels;
import com.voltcanary.aiagent.orchestration.routing.AttemptLedger;
import com.voltcanary.aiagent.orchestration.routing.RoutedModelCalls;
import com.voltcanary.aiagent.llm.adapter.AiModelResolver;
import com.voltcanary.aiagent.port.AssistantTraces;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Main application composition. Each phase shares the same A2 ledger and immutable pinned route. */
public final class PrimaryRuntime {
    public record Infrastructure(ObjectMapper mapper, ProtocolValidator validator, IntentPlanStore plans, AttemptLedger ledger,
            ContextSources.History history, ContextSources.Reviews reviews, ContextLoading loading,
            OrchestrationPorts.Receipts receipts, AssistantTraces traces, AiModelResolver resolver, AiAgentModelRunRecorder runs,
            com.voltcanary.aiagent.orchestration.plan.AgentGraphRunner graphRunner,
            CollaborationStore collaboration, AuthorizedPlanningContext authority,
            io.micrometer.observation.ObservationRegistry observations, Clock clock) { }
    public record Engine(IntentOrchestration turns, IntentPlanActions actions, IntentOrchestration.Skills skills,
            String policyId, String policyVersion) { }
    private final Infrastructure ports;
    private final SkillRegistry registry;
    private final PrimaryDomain domain;
    private final PrimaryExecutionProfile profile;
    private final WidgetCatalogueSearch catalogueSearch;
    public String graphEngine() { return ports.graphRunner().engine(); }
    public String graphDefinition() { return ports.graphRunner().version(); }
    public PrimaryRuntime(Infrastructure ports, SkillRegistry registry, PrimaryDomain domain) {
        this(ports, registry, domain, PrimaryExecutionProfile.STANDARD);
    }
    public PrimaryRuntime(Infrastructure ports, SkillRegistry registry, PrimaryDomain domain, PrimaryExecutionProfile profile) {
        this(ports, registry, domain, profile, new WidgetCatalogueSearch(ports.mapper(), null));
    }
    public PrimaryRuntime(Infrastructure ports, SkillRegistry registry, PrimaryDomain domain, PrimaryExecutionProfile profile,
            WidgetCatalogueSearch catalogueSearch) {
        this.ports = ports;
        this.registry = registry;
        this.domain = domain;
        this.profile = profile;
        this.catalogueSearch = catalogueSearch;
    }
    public IntentPlanActions actions() {
        return new IntentPlanActions(ports.plans(), ports.receipts(), ports.mapper(), ports.validator(), ports.traces(), ports.ledger(), ports.graphRunner());
    }
    public Engine engine(OrchestrationPorts.Session session, AiResolvedModel selected) {
        var capabilities = domain.capabilities(session);
        var allNames = java.util.stream.Stream.concat(Arrays.stream(AgentTool.values()).map(Enum::name),
                java.util.stream.Stream.of(DashboardSkillContract.REFINE))
                .filter(intent -> !AgentTool.CREATE_DATA_SOURCE.name().equals(intent) || ports.authority().hslSourceAvailable())
                .toList();
        var available = registry.catalogue(Set.copyOf(allNames), capabilities).stream().map(AssistantProtocol.IntentSummary::intentId)
                .collect(java.util.stream.Collectors.toSet());
        var names = allNames.stream().filter(available::contains).toList();
        String model = selected.modelConfig().getId().toString();
        String provider = selected.provider().getId().toString();
        String policyId = "primary-" + model;
        String outputMode = com.voltcanary.aiagent.adapter.PrimaryOutputCapabilities.mode(selected);
        long output = Math.min(12000, selected.modelConfig().getMaxOutputTokens() == null ? 12000 : selected.modelConfig().getMaxOutputTokens());
        String version = SkillRegistry.digest(selected.modelConfig().getModelId() + ":" + selected.provider().getBaseUrl() + ":" + output
                + ":" + selected.modelConfig().getTimeoutMs() + ":" + selected.modelConfig().getTemperature() + ":" + selected.modelConfig().getTopP()
                + ":" + profile + ":" + outputMode + ":" + com.voltcanary.aiagent.orchestration.routing.StrictOutputProjection.VERSION
                + ":" + PrimaryPlannerGuide.VERSION + ":" + DashboardAssessmentGuide.VERSION + ":" + NameConsultation.VERSION
                + ":" + SkillRegistry.digest(BoundedSkillRequests.TRUST_POLICY)
                + ":" + com.voltcanary.aiagent.adapter.PrimaryPromptProjection.VERSION
                + ":" + com.voltcanary.aiagent.adapter.PrimaryPlannerProjection.VERSION + ":" + catalogueSearch.identity()
                + ":" + registry.versionDigest(Set.copyOf(names), capabilities)).substring(0, 20);
        var turnLimits = new AssistantProtocol.Limits(6L, 700_000L, 72_000L, 240_000L, 600_000L, "USD");
        var planLimits = new AssistantProtocol.Limits(60L, 7_000_000L, 720_000L, 3_600_000L, 6_000_000L, "USD");
        var candidate = new AssistantProtocol.Candidate(provider, model, null, 128_000L, 112_000L, output, outputMode, null);
        var policy = new AssistantProtocol.RoutingPolicy("assistant-v2", "routing.policy", policyId, version, "PINNED", model,
                List.of(new AssistantProtocol.RoutingPolicyProfilesItem("primary", names,
                        profile.modelJudge() ? List.of("PLANNER", "STEP", "REPAIR", "VERIFY") : List.of("PLANNER", "STEP", "REPAIR"), List.of(candidate), false),
                        new AssistantProtocol.RoutingPolicyProfilesItem("name-assessment", List.of(NameConsultation.ROLE, NameConsultation.CHILD_ROLE),
                                List.of("CONSULT"), List.of(candidate), false)),
                turnLimits, planLimits, "RESERVED_UPPER_BOUND", 100_000L, BigDecimal.ONE, null);
        var skills = new IntentOrchestration.Skills(Set.copyOf(names), capabilities);
        var requests = new BoundedSkillRequests(ports.mapper(), ports.validator(), registry, ports.plans(), ports.history(), ports.loading(),
                (input, current) -> skills, ports.receipts(), ports.reviews(), domain, ports.clock(), 220_000, ports.ledger(), policy);
        var transport = new PrimaryModelTransport(ports.mapper(), selected, session.user(), ports.runs(), catalogueSearch, ports.observations());
        var availability = new ExistingModelAvailability(ports.resolver(), session.user(), Map.of(model, Set.of(outputMode)), Set.of(), Map.of());
        var calls = new RoutedModelCalls(ports.mapper(), ports.validator(), registry, ports.ledger(), ports.traces(), ports.clock());
        var models = new RoutedIntentModels(calls, requests, (input, current, phase) -> new RoutedIntentModels.Route(ports.mapper().valueToTree(policy),
                new RoutedModelCalls.Selection("primary", phase, null, output, 4096L), skills.authorized(), skills.capabilities(), availability, transport,
                proposal -> {
                    if (phase.equals("VERIFY")) return;
                    if (!phase.equals("PLANNER")) { domain.validateProposal(input, proposal); return; }
                    PrimaryPlannerChecks.validate(input, proposal);
                    var decision = ports.validator().read("plannerResponse", proposal, AssistantProtocol.PlannerResponse.class);
                    if (!Set.of("PLAN", "AMEND").contains(decision.decision())) return;
                    try {
                        new IntentPlanGraph(registry, ports.validator(), ports.mapper()).apply(input.state(), decision,
                                ports.plans().sourceMessages(current.owner(), input.state().conversationId()), input.currentMessage().messageId().toString(), true,
                                skills.authorized(), skills.capabilities());
                    } catch (com.voltcanary.aiagent.orchestration.plan.IntentPlans.Rejected invalid) {
                        throw new com.voltcanary.aiagent.orchestration.routing.ModelProposalRejected(proposal,
                                "Planner graph rejected: " + invalid.getMessage() + ". Use exact facts[].factId for fact_ref and preserve actual planSummary dependencies. A replacement must not depend on a superseded step. "
                                        + "Every step goalIds must refer to retained goals or proposedGoals. Preserve retained goals and existing receipts; do not invent references.");
                    }
                }), ports.plans(), ports.validator(), profile.automaticCorrections(), ports.graphRunner());
        var consultation = new NameConsultation(ports.collaboration(), ports.mapper());
        var runtime = new IntentOrchestration.Runtime(ports.mapper(), ports.validator(), ports.clock(), Duration.ofHours(12), ports.traces(), ports.graphRunner(),
                (input, step, current, nameRequested) -> {
                    var token = input.token();
                    var correlation = new AssistantTraceRecorder.Correlation(token.owner(), token.conversationId(), token.messageId(),
                            token.requestId(), token.requestId());
                    return consultation.run(input, step, (root, task) -> {
                        if (!root.owner().equals(ports.authority().scope(current.user(), current.authorization()))) {
                            throw Requirements.conflict("CONSULTATION_AUTHORITY_CHANGED");
                        }
                        return new ConsultationWorker.Evidence(Map.of(), Map.of(), Set.of(), root.owner().accountId().toString());
                    }, new ConsultationWorker.Route(ports.mapper().valueToTree(policy),
                            new RoutedModelCalls.Selection("name-assessment", "CONSULT", null, output, 4096L), availability, correlation, transport),
                            contracts -> new ConsultationWorker(ports.collaboration(),
                                    new com.voltcanary.aiagent.orchestration.collaboration.ConsultationPolicy(contracts, ports.mapper()),
                                    contracts, calls, ports.ledger(), ports.graphRunner(), ports.mapper(), ports.validator()), ports.validator(), nameRequested,
                            (task, request) -> {
                                if (!input.resolvedInputs().containsKey("deviceId") && !input.resolvedInputs().containsKey("deviceIds")) return null;
                                var loaded = ports.loading().load(input, current, skills, step, new AssistantProtocol.LoaderRequest(
                                        request.path("loaderId").asText(), request.path("argumentsSchemaId").asText(),
                                        request.path("arguments"), null));
                                if (!"COMPLETE".equals(loaded.completeness())) return null;
                                var result = ports.mapper().createObjectNode().put("loaderId", loaded.loaderId())
                                        .put("snapshotId", loaded.snapshotId());
                                var labels = result.putArray("deviceLabels");
                                var devices = loaded.data().at("/context/dashboardDevices");
                                if (!devices.isArray() || devices.isEmpty() || devices.size() > 20) return null;
                                for (var device : devices) {
                                    var label = device.path("name");
                                    if (label.isTextual() && label.textValue().length() <= 255) labels.add(label.textValue());
                                }
                                return result;
                            }, java.util.stream.StreamSupport.stream(ports.validator().parse(
                                    ports.plans().request(token.owner(), token.planId(), token.requestId())).path("selection").spliterator(), false)
                                    .anyMatch(item -> "name".equals(item.path("field").asText())
                                            && item.path("ref").equals(input.resolvedInputs().get("name"))));
                });
        var assessment = new DashboardAssessment(ports.mapper(), models, ports.traces());
        return new Engine(new IntentOrchestration(ports.plans(), new IntentPlanGraph(registry, ports.validator(), ports.mapper()), models,
                (input, skill, command, current) -> domain.prepare(input, skill, command, current, selected,
                        (candidatePlan, context) -> { if (profile.modelJudge()) assessment.verify(input, current, candidatePlan); }), ports.receipts(), runtime),
                actions(), skills, policyId, version);
    }
}
