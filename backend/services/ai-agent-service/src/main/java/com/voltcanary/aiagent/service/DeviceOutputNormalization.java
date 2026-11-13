package com.voltcanary.aiagent.service;

import com.voltcanary.aiagent.llm.LlmActionDraft;
import com.voltcanary.aiagent.llm.LlmPlanDraft;
import com.voltcanary.aiagent.trace.AssistantTrace;
import java.util.LinkedHashMap;
import java.util.Map;

/** One deterministic pass. Unknown fields and conflicting values remain rejected by the sanitizer. */
final class DeviceOutputNormalization {
    private DeviceOutputNormalization() { }

    static LlmPlanDraft normalize(LlmPlanDraft draft, Map<String, Object> context) {
        if (draft.actions() == null) return draft;
        var actions = draft.actions().stream().map(action -> {
            if (!"CREATE_DEVICE".equalsIgnoreCase(action.tool()) || action.parameters() == null) return action;
            var parameters = new LinkedHashMap<>(action.parameters());
            var changed = new java.util.ArrayList<String>();
            for (var alias : Map.of("deviceName", "name", "deviceType", "type").entrySet()) {
                Object value = parameters.get(alias.getKey());
                if (value instanceof String && (!parameters.containsKey(alias.getValue()) || value.equals(parameters.get(alias.getValue())))) {
                    parameters.put(alias.getValue(), value);
                    parameters.remove(alias.getKey());
                    changed.add(alias.getKey() + " -> " + alias.getValue());
                }
            }
            if (parameters.get("typeRef") instanceof String supplied && context.get("deviceTypes") instanceof java.util.List<?> types) {
                var matches = types.stream().filter(Map.class::isInstance).map(value -> (Map<?, ?>) value)
                        .filter(value -> supplied.equals(value.get("ref")) || supplied.equals(value.get("code"))).toList();
                if (matches.size() == 1) {
                    String target = supplied.equals(matches.getFirst().get("ref")) ? "deviceTypeRef" : "type";
                    if (!parameters.containsKey(target) || supplied.equals(parameters.get(target))) {
                        parameters.put(target, supplied);
                        parameters.remove("typeRef");
                        changed.add("typeRef -> " + target + " (current catalogue)");
                    }
                }
            }
            // An exact duplicate of server-owned HSL intent is only a redundant hint. No network config is accepted.
            if (context.containsKey("deviceSetupCapabilities") && context.get("originalDeviceRequest") instanceof String original) {
                try {
                    var expected = DeviceConnectionIntent.hsl(original);
                    if (!expected.isEmpty() && expected.equals(parameters.get("connection"))) {
                        parameters.remove("connection");
                        changed.add("connection -> server-owned intent");
                    }
                } catch (IllegalArgumentException invalid) {
                    // The compiler will ask for the genuinely ambiguous transport identity.
                }
            }
            if (changed.isEmpty()) return action;
            AssistantTrace.event("DEVICE_NORMALIZATION", "SUCCEEDED", "EQUIVALENT_FIELDS", Map.of("rules", changed));
            return new LlmActionDraft(action.tool(), action.title(), action.description(), java.util.Collections.unmodifiableMap(parameters));
        }).toList();
        return new LlmPlanDraft(draft.provider(), draft.model(), draft.summary(), draft.explanation(), actions, draft.clarification());
    }
}
