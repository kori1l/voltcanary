package com.voltcanary.aiagent.vehicle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.aiagent.adapter.PrimaryOrchestrationJournal;
import com.voltcanary.aiagent.llm.adapter.AiResolvedModel;
import com.voltcanary.aiagent.model.AgentAction;
import com.voltcanary.aiagent.model.AgentPlan;
import com.voltcanary.aiagent.model.AgentTool;
import com.voltcanary.aiagent.nativeops.NativeTableWorkflow;
import com.voltcanary.aiagent.orchestration.context.ContextSources;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol;
import com.voltcanary.aiagent.orchestration.contract.ProtocolValidator;
import com.voltcanary.aiagent.orchestration.contract.SkillRegistry;
import com.voltcanary.aiagent.orchestration.plan.IntentPlanStore;
import com.voltcanary.aiagent.orchestration.plan.IntentPlans;
import com.voltcanary.aiagent.orchestration.plan.OrchestrationPorts;
import com.voltcanary.aiagent.service.AuthorizedPlanningContext;
import com.voltcanary.aiagent.service.PlanStore;
import com.voltcanary.aiagent.service.PrimaryDomain;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import lombok.RequiredArgsConstructor;

/** Focused Vehicles extension using the existing planner, model reservation and immutable plan store. */
@RequiredArgsConstructor
public final class VehiclePrimaryDomain implements PrimaryDomain {
    private final PrimaryDomain delegate;
    private final VehicleMonitoringOwner owner;
    private final ObjectMapper mapper;
    private final ProtocolValidator validator;
    private final IntentPlanStore plans;
    private final PlanStore reviews;
    private final AuthorizedPlanningContext authority;
    private final PrimaryOrchestrationJournal journal;

    @Override public String id() { return delegate.id(); }
    @Override public JsonNode resultSchema() { return delegate.resultSchema(); }
    @Override public Set<String> capabilities(OrchestrationPorts.Session session) { return delegate.capabilities(session); }
    private boolean vehicle(IntentPlans.ModelInput input) { return input.state().step(NativeTableWorkflow.step(input)).intentId().equals(VehicleMonitoringSkill.INTENT); }
    @Override public boolean includesBaseReview(IntentPlans.ModelInput input) { return !vehicle(input) && delegate.includesBaseReview(input); }
    @Override public List<AssistantProtocol.Choice> plannerChoices(IntentPlans.ModelInput input, OrchestrationPorts.Session session) { return delegate.plannerChoices(input, session); }
    @Override public AssistantProtocol.LoadedContext read(IntentPlans.ModelInput input, OrchestrationPorts.Session session) {
        if (!vehicle(input)) return delegate.read(input, session);
        var page = context(input, session);
        return new AssistantProtocol.LoadedContext(id(), page.snapshotId(), page.asOf().toString(), "COMPLETE", null, page.data());
    }
    @Override public ContextSources.Page load(IntentPlans.ModelInput input, OrchestrationPorts.Session session, JsonNode args, ContextSources.Position position) {
        if (!vehicle(input)) return delegate.load(input, session, args, position);
        if (position != null || !args.isObject() || !args.isEmpty()) throw IntentPlans.reject("VEHICLE_CONTEXT_ARGUMENTS_INVALID");
        return context(input, session);
    }
    private UUID selectedDevice(IntentPlans.ModelInput input, OrchestrationPorts.Session session) {
        var resolved = input.resolvedInputs().get("deviceId");
        if (resolved != null) return UUID.fromString(resolved.asText());
        var request = validator.parse(plans.request(session.owner(), input.state().planId(), input.token().requestId()));
        UUID selected = null;
        for (var item : request.path("selection")) if (item.path("field").asText().equals("deviceId")) {
            if (selected != null) throw IntentPlans.reject("SELECT_ONE_VEHICLE");
            selected = UUID.fromString(item.path("ref").asText());
        }
        return selected;
    }
    private String selectedSource(IntentPlans.ModelInput input, OrchestrationPorts.Session session) {
        var request = validator.parse(plans.request(session.owner(), input.state().planId(), input.token().requestId()));
        for (var item : request.path("selection")) if (item.path("field").asText().equals("sourceId")) return UUID.fromString(item.path("ref").asText()).toString();
        return null;
    }
    private ContextSources.Page context(IntentPlans.ModelInput input, OrchestrationPorts.Session session) {
        checkSession(session);
        var data = mapper.createObjectNode();
        var context = data.putObject("context");
        var submitted = validator.parse(plans.request(session.owner(), input.state().planId(), input.token().requestId()));
        for (var item : submitted.path("selection")) if (item.path("field").asText().equals("vehiclePoint")) {
            context.set("selectedPoint", mapper.valueToTree(VehicleMonitoringPoint.parse(item.path("ref").asText())));
        }
        UUID device = selectedDevice(input, session);
        if (device == null) context.put("selectionRequired", true);
        else {
            var resource = owner.device(session.authorization(), device);
            if (!resource.path("id").asText().equals(device.toString()) || !resource.path("type").asText().equals("VEHICLE")) throw IntentPlans.reject("SELECT_AN_AUTHORIZED_VEHICLE");
            context.putObject("vehicle").put("id", device.toString()).put("name", resource.path("name").asText()).put("type", "VEHICLE");
            context.set("monitoring", owner.read(session.authorization(), session.owner().userId(), device, "/snapshot"));
            var sources = owner.sources(session.authorization(), device);
            if (!sources.isArray() || sources.size() > 100) throw IntentPlans.reject("NARROW_VEHICLE_SOURCE_SELECTION");
            var offered = context.putArray("sources");
            for (var source : sources) offered.addObject().put("id", source.path("id").asText())
                    .put("externalId", source.path("externalId").asText()).put("protocol", source.path("protocol").asText())
                    .put("enabled", source.path("enabled").asBoolean(true)).put("status", source.path("status").asText("ACTIVE"));
            context.put("selectedSourceId", selectedSource(input, session));
        }
        data.set("operationSchema", VehicleMonitoringSkill.schema(mapper));
        return new ContextSources.Page(SkillRegistry.digest(data.toString()), Instant.now(), data, null);
    }
    @Override public void validateProposal(IntentPlans.ModelInput input, JsonNode response) {
        if (!vehicle(input)) delegate.validateProposal(input, response);
        else if (response.path("outcome").asText().equals("PREPARED")) validator.validateRegistered(VehicleMonitoringSkill.schema(mapper), response.at("/command/payload"));
    }
    @Override public OrchestrationPorts.Prepared prepare(IntentPlans.ModelInput input, AssistantProtocol.Manifest skill, AssistantProtocol.Command command,
            OrchestrationPorts.Session session, AiResolvedModel selected, BiConsumer<AgentPlan, AuthorizedPlanningContext.Prepared> candidateCheck) {
        if (!vehicle(input)) return delegate.prepare(input, skill, command, session, selected, candidateCheck);
        checkSession(session);
        if (!command.schemaId().equals(VehicleMonitoringSkill.INTENT + ":command:1") || !command.schemaVersion().equals("1")) throw IntentPlans.reject("VEHICLE_SKILL_VERSION_CHANGED");
        validator.validateRegistered(VehicleMonitoringSkill.schema(mapper), command.payload());
        var payload = command.payload();
        UUID device = selectedDevice(input, session);
        if (device == null || !device.toString().equals(payload.path("deviceId").asText())) throw IntentPlans.reject("VEHICLE_SELECTION_CHANGED");
        var context = context(input, session).data().path("context");
        var sources = context.path("sources");
        String requested = payload.path("sourceId").isNull() ? null : payload.path("sourceId").asText();
        String selectedSource = selectedSource(input, session);
        var eligible = new java.util.ArrayList<String>();
        for (var source : sources) if (source.path("protocol").asText().equals("REST") && source.path("enabled").asBoolean(true)
                && source.path("status").asText("ACTIVE").equals("ACTIVE")) eligible.add(source.path("id").asText());
        if (eligible.size() > 1 && selectedSource == null || requested != null && !eligible.contains(requested) || selectedSource != null && !selectedSource.equals(requested)
                || requested == null && !eligible.isEmpty()) throw IntentPlans.reject("SELECT_THE_AUTHORIZED_GPS_SOURCE");
        var snapshot = context.path("monitoring");
        if (snapshot.path("revision").asLong() != payload.path("expectedRevision").asLong()) throw IntentPlans.reject("MONITORING_REVISION_CHANGED");
        String identity = input.state().planId() + "/" + NativeTableWorkflow.step(input) + "/" + input.token().requestId();
        String key = "ai-vehicle-" + SkillRegistry.digest(identity + payload);
        var after = VehicleMonitoringDraft.apply(snapshot.path("settings"), payload, identity);
        var ownerReview = owner.post(session.authorization(), session.owner().userId(), device, "/reviews",
                Map.of("expectedRevision", snapshot.path("revision").asLong(), "settings", after, "idempotencyKey", key));
        if (!ownerReview.path("deviceId").asText().equals(device.toString()) || !ownerReview.path("after").equals(after)
                || !ownerReview.path("before").equals(snapshot.path("settings")) || !ownerReview.path("status").asText().equals("PREPARED")) throw IntentPlans.reject("MONITORING_REVIEW_CHANGED");
        var parameters = new java.util.LinkedHashMap<String, Object>();
        parameters.put("userId", session.owner().userId().toString());
        parameters.put("deviceId", device.toString());
        parameters.put("review", mapper.convertValue(ownerReview, Map.class));
        if (requested != null) parameters.put("sourceId", requested);
        var action = new AgentAction(UUID.randomUUID(), AgentTool.UPDATE_VEHICLE_MONITORING, "Настроить мониторинг «" + context.path("vehicle").path("name").asText() + "»",
                "Изменения геозон и порогов в существующей вкладке Vehicles", true, parameters);
        var plan = new AgentPlan(UUID.randomUUID(), session.owner().userId(), input.currentMessage().text(), "Настройки мониторинга транспорта",
                "Проверьте изменения перед подтверждением. Фоновые уведомления не включаются.", List.of("assistant-v2"), List.of(action), Instant.now());
        var saved = reviews.saveBounded(plan, new AuthorizedPlanningContext.Prepared(session.owner(), Map.of(),
                List.of(new AuthorizedPlanningContext.ResourceRef(device.toString(), "device", device.toString(), Map.of()))), Instant.parse(ownerReview.path("expiresAt").asText()));
        journal.bind(saved.plan().id(), input.state().planId(), NativeTableWorkflow.step(input));
        return new OrchestrationPorts.Prepared(new IntentPlans.Review(saved.plan().id(), saved.version(), saved.digest(), List.of(action.id()), true), List.of());
    }
    private void checkSession(OrchestrationPorts.Session session) {
        if (!authority.scope(session.user(), session.authorization()).equals(session.owner())) throw IntentPlans.reject("VEHICLE_SESSION_CHANGED");
    }
}
