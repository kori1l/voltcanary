package com.voltcanary.aiagent.nativeops;

import com.fasterxml.jackson.databind.JsonNode;
import com.voltcanary.aiagent.llm.adapter.AiResolvedModel;
import com.voltcanary.aiagent.model.AgentPlan;
import com.voltcanary.aiagent.orchestration.context.ContextSources;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol;
import com.voltcanary.aiagent.orchestration.plan.IntentPlans;
import com.voltcanary.aiagent.orchestration.plan.OrchestrationPorts;
import com.voltcanary.aiagent.service.AuthorizedPlanningContext;
import com.voltcanary.aiagent.service.PrimaryDomain;
import com.voltcanary.aiagent.service.PrimaryDomainPreparation;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;

/** Dispatches one new generic owner slice without expanding dashboard/device preparation. */
public final class NativePrimaryDomain implements PrimaryDomain {
    private final PrimaryDomainPreparation existing;
    private final NativeTableWorkflow nativeTables;
    public NativePrimaryDomain(PrimaryDomainPreparation existing, NativeTableWorkflow nativeTables) {
        this.existing = existing;
        this.nativeTables = nativeTables;
    }
    @Override public String id() { return existing.id(); }
    @Override public JsonNode resultSchema() { return existing.resultSchema(); }
    @Override public Set<String> capabilities(OrchestrationPorts.Session session) { return nativeTables.capabilities(session); }
    @Override public boolean includesBaseReview(IntentPlans.ModelInput input) { return !nativeStep(input) && existing.includesBaseReview(input); }
    @Override public List<AssistantProtocol.Choice> plannerChoices(IntentPlans.ModelInput input, OrchestrationPorts.Session session) {
        return existing.plannerChoices(input, session);
    }
    @Override public AssistantProtocol.LoadedContext read(IntentPlans.ModelInput input, OrchestrationPorts.Session session) {
        if (!nativeStep(input)) return existing.read(input, session);
        var page = nativeTables.context(session);
        return new AssistantProtocol.LoadedContext(id(), page.snapshotId(), page.asOf().toString(), "COMPLETE", null, page.data());
    }
    @Override public ContextSources.Page load(IntentPlans.ModelInput input, OrchestrationPorts.Session session, JsonNode arguments, ContextSources.Position position) {
        if (!nativeStep(input)) return existing.load(input, session, arguments, position);
        if (position != null || !arguments.isObject() || !arguments.isEmpty()) throw IntentPlans.reject("NATIVE_CONTEXT_ARGUMENTS_INVALID");
        return nativeTables.context(session);
    }
    @Override public void validateProposal(IntentPlans.ModelInput input, JsonNode response) {
        if (!nativeStep(input)) {
            existing.validateProposal(input, response);
            return;
        }
        try { nativeTables.validate(response); }
        catch (IllegalArgumentException invalid) {
            throw new com.voltcanary.aiagent.orchestration.routing.ModelProposalRejected(response, "Native collection contract: " + invalid.getMessage());
        }
    }
    @Override public OrchestrationPorts.Prepared prepare(IntentPlans.ModelInput input, AssistantProtocol.Manifest skill,
            AssistantProtocol.Command command, OrchestrationPorts.Session session, AiResolvedModel selected,
            BiConsumer<AgentPlan, AuthorizedPlanningContext.Prepared> candidateCheck) {
        return nativeStep(input) ? nativeTables.prepare(input, skill, command, session, selected)
                : existing.prepare(input, skill, command, session, selected, candidateCheck);
    }
    private static boolean nativeStep(IntentPlans.ModelInput input) {
        return input.state().step(NativeTableWorkflow.step(input)).intentId().equals(NativeTableSkill.INTENT);
    }
}
