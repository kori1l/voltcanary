package com.voltcanary.aiagent.orchestration.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.BindingFactRef;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.BindingLiteral;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.BindingStepOutput;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.Fact;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import static com.voltcanary.aiagent.orchestration.plan.IntentPlans.reject;

@RequiredArgsConstructor
public final class ConfirmedInputs {
    private final OrchestrationPorts.Receipts receipts;

    public Map<String, JsonNode> resolve(IntentPlans.State state, String stepId, OrchestrationPorts.Session session) {
        var step = state.step(stepId);
        for (String dependency : step.dependsOn()) if (!state.step(dependency).status().equals("SUCCEEDED")) throw reject("DEPENDENCY_NOT_CONFIRMED");
        var result = new LinkedHashMap<String, JsonNode>();
        for (var input : step.inputs()) {
            JsonNode value;
            if (input.binding() instanceof BindingLiteral literal) value = literal.value().deepCopy();
            else if (input.binding() instanceof BindingFactRef ref) {
                var confirmed = state.facts().stream().filter(item -> item.fact().factId().equals(ref.factId())).findFirst()
                        .orElseThrow(() -> reject("FACT_UNAVAILABLE"));
                if (!confirmed.fact().confirmation().equals("CONFIRMED")) throw reject("FACT_NOT_CONFIRMED");
                if (confirmed.output() == null) {
                    if (!List.of("USER_EXPLICIT", "USER_CONFIRMED").contains(confirmed.fact().origin())) throw reject("FACT_PROVENANCE_INVALID");
                    value = confirmed.fact().value().deepCopy();
                } else value = receipts.output(confirmed.output(), session);
            } else if (input.binding() instanceof BindingStepOutput output) {
                if (!state.step(output.stepId()).status().equals("SUCCEEDED")) throw reject("DEPENDENCY_NOT_CONFIRMED");
                var binding = state.outputs().getOrDefault(output.stepId(), List.of()).stream().filter(item -> item.key().equals(output.outputKey())).findFirst()
                        .orElseThrow(() -> reject("OUTPUT_UNAVAILABLE"));
                value = receipts.output(binding, session);
            } else throw reject("BINDING_UNSUPPORTED");
            result.put(input.field(), value);
        }
        return Map.copyOf(result);
    }

    public IntentPlans.Confirmed fact(IntentPlans.Output output, String factId, String sourceMessage,
            OrchestrationPorts.Session session) {
        var value = receipts.output(output, session);
        return new IntentPlans.Confirmed(new Fact(factId, output.key(), value, "DOMAIN_RECEIPT", sourceMessage,
                output.reviewId() + ":" + output.actionId(), "CONFIRMED"), output.type(), output);
    }
}
