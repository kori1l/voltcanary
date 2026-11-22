package com.voltcanary.aiagent.port;

import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.TurnSubmitSelectionItem;
import com.voltcanary.aiagent.service.AuthorizedPlanningContext.Prepared;
import com.voltcanary.aiagent.service.AuthorizedPlanningContext.Scope;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persisted preparation snapshots and bounded selection history for the primary workflow. */
public interface WorkflowContextStore {
    List<List<TurnSubmitSelectionItem>> recentSelections(Scope owner, UUID conversation);
    Optional<Prepared> load(UUID plan, UUID request, String step);
    void save(UUID plan, UUID request, String step, Prepared context);
}
