package com.voltcanary.aiagent.service;

import com.voltcanary.aiagent.llm.LlmPlanDraft;
import java.util.List;
import java.util.Map;

/** One provider-side schema correction; rejected fields never reach execution. */
final class DashboardCompositionRepair {
    private DashboardCompositionRepair() { }
    static boolean required(LlmPlanDraft draft) { return required(draft, Map.of()); }
    static boolean required(LlmPlanDraft draft, Map<String, Object> input) {
        if (draft.actions() == null) return false;
        if (DashboardOperationProtocol.VERSION.equals(input.get("dashboardPlanningProtocol"))) {
            return draft.actions().stream().anyMatch(action -> !"CREATE_DASHBOARD".equals(action.tool())
                    || action.parameters() == null || !DashboardOperationProtocol.VERSION.equals(action.parameters().get("protocol"))
                    || !(action.parameters().get("operations") instanceof List<?> operations) || operations.isEmpty());
        }
        return draft.actions().stream().filter(action -> "CREATE_DASHBOARD".equals(action.tool()))
                .anyMatch(action -> action.parameters() != null && (input.get("previousDashboardIntent") instanceof Map<?, ?> && (action.parameters().containsKey("widgets") || emptyEdit(action.parameters()))
                        || input.containsKey("dashboardWidgetTypes") && !input.containsKey("previousDashboardIntent") && !action.parameters().containsKey("widgets")
                        || action.parameters().get("widgets") instanceof List<?> widgets
                        && widgets.stream().anyMatch(item -> !(item instanceof Map<?, ?> panel)
                        || !DashboardCompositionCompiler.PANEL_FIELDS.containsAll(panel.keySet()))));
    }
    private static boolean emptyEdit(Map<String, Object> values) {
        return values.isEmpty() || values.keySet().stream().allMatch(key -> List.of("widgetEdits", "addWidgets").contains(key))
                && values.values().stream().allMatch(value -> value instanceof List<?> list && list.isEmpty());
    }
    static Map<String, Object> context(LlmPlanDraft draft) {
        return Map.of("rejectedDraft", draft, "allowedWidgetFields", DashboardCompositionCompiler.PANEL_FIELDS.stream().sorted().toList(),
                "instruction", "For a new dashboard emit parameters containing name, deviceRefs and a nonempty widgets list; never an empty object or a legacy template. If name is missing return actions:[] plus structured clarification. Use widgetEdits/addWidgets/moveWidgets/widgetOrder for refinements; never re-emit widgets. Never return empty parameters or empty edit lists for a requested change. Correct unsupported widget fields yourself. Preserve requested content. Never ask the user to know widget parameter names. No raw queries, bindings or definitions.");
    }
}
