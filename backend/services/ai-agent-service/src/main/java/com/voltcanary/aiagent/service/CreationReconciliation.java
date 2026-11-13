package com.voltcanary.aiagent.service;

import com.voltcanary.aiagent.dto.ActionExecutionResponse;
import com.voltcanary.aiagent.model.AgentAction;
import com.voltcanary.aiagent.model.AgentTool;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Only owning APIs with durable, atomic receipts can be replayed after an uncertain response. */
@Component
public class CreationReconciliation {
    private final ToolExecutor executor;

    public CreationReconciliation(ToolExecutor executor) { this.executor = executor; }

    public boolean supports(AgentAction action) {
        return action.tool() == AgentTool.CREATE_DEVICE || action.tool() == AgentTool.CREATE_NATIVE_TABLE || action.tool() == AgentTool.CREATE_DASHBOARD || action.tool() == AgentTool.UPDATE_DASHBOARD_DRAFT
                || action.tool() == AgentTool.CREATE_DATA_SOURCE && "HSL_HFP".equals(action.parameters().get("provider"));
    }

    public ActionExecutionResponse reconcile(AgentAction action, String authorization, UUID user,
            Map<String, Object> outputs, String stableKey) {
        return reconcile(action, authorization, user, outputs, stableKey, false);
    }

    public ActionExecutionResponse reconcile(AgentAction action, String authorization, UUID user,
            Map<String, Object> outputs, String stableKey, boolean inspectOnly) {
        if (!supports(action)) throw new IllegalArgumentException("This owning API has no creation receipt");
        if (inspectOnly && action.tool() == AgentTool.CREATE_NATIVE_TABLE) return executor.inspectNativeOutcome(action, authorization, outputs);
        if (inspectOnly) throw new IllegalArgumentException("Read-only recovery is unavailable for this action");
        return (action.tool() == AgentTool.CREATE_DASHBOARD || action.tool() == AgentTool.UPDATE_DASHBOARD_DRAFT || action.tool() == AgentTool.CREATE_NATIVE_TABLE) ? executor.reconcile(action, authorization, outputs, stableKey)
                : executor.execute(action, authorization, user, outputs, stableKey);
    }
}
