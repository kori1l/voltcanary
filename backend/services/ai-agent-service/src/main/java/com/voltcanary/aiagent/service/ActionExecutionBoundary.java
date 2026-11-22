package com.voltcanary.aiagent.service;

import com.voltcanary.aiagent.dto.ActionExecutionResponse;
import com.voltcanary.aiagent.model.AgentAction;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

@Component
public class ActionExecutionBoundary {

    public ActionExecutionResponse run(
        AgentAction action,
        Map<String, Object> outputs,
        Supplier<Map<String, Object>> operation
    ) {
        try {
            Map<String, Object> safe = AssistantResultRedaction.result(operation.get());
            outputs.putAll(safe);
            return response(action, "SUCCESS", safe, null);
        } catch (IllegalArgumentException rejected) {
            return response(
                action,
                "FAILED",
                Map.of(),
                "Reviewed inputs are no longer valid; prepare and review again"
            );
        } catch (RestClientResponseException rejected) {
            boolean unknown =
                action.requiresConfirmation() && rejected.getStatusCode().is5xxServerError();
            return response(
                action,
                unknown ? "UNKNOWN" : "FAILED",
                Map.of(),
                unknown
                    ? "Domain outcome is unknown; reconcile before retrying"
                    : "Domain operation was rejected"
            );
        } catch (ResourceAccessException unavailable) {
            return response(
                action,
                action.requiresConfirmation() ? "UNKNOWN" : "FAILED",
                Map.of(),
                "Domain response unavailable; no automatic retry was performed"
            );
        } catch (Exception invalidResponse) {
            return response(
                action,
                action.requiresConfirmation() ? "UNKNOWN" : "FAILED",
                Map.of(),
                "Action response could not be verified; inspect recorded domain state"
            );
        }
    }

    private ActionExecutionResponse response(
        AgentAction action,
        String status,
        Map<String, Object> result,
        String error
    ) {
        return new ActionExecutionResponse(
            action.id(),
            action.tool().name(),
            status,
            result,
            error
        );
    }
}
