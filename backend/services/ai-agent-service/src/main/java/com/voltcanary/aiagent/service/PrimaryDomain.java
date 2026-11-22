package com.voltcanary.aiagent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.voltcanary.aiagent.llm.adapter.AiResolvedModel;
import com.voltcanary.aiagent.model.AgentPlan;
import com.voltcanary.aiagent.orchestration.context.ContextSources;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol;
import com.voltcanary.aiagent.orchestration.plan.IntentPlans;
import com.voltcanary.aiagent.orchestration.plan.OrchestrationPorts;
import java.util.Set;
import java.util.function.BiConsumer;

/** Shared orchestration port; focused domain extensions retain one ledger, model pipeline and review store. */
public interface PrimaryDomain extends ContextSources.Loader, ContextSources.Initial {
    default Set<String> capabilities(OrchestrationPorts.Session session) {
        return Set.of();
    }

    void validateProposal(IntentPlans.ModelInput input, JsonNode response);
    OrchestrationPorts.Prepared prepare(
        IntentPlans.ModelInput input,
        AssistantProtocol.Manifest skill,
        AssistantProtocol.Command command,
        OrchestrationPorts.Session session,
        AiResolvedModel selected,
        BiConsumer<AgentPlan, AuthorizedPlanningContext.Prepared> candidateCheck
    );
}
