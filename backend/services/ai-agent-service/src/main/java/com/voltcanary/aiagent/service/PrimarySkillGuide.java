package com.voltcanary.aiagent.service;

import com.voltcanary.aiagent.model.AgentTool;

/** Versioned package sections selected by registered intent, never by user prose. */
final class PrimarySkillGuide {

    static final String VERSION = "2";

    private PrimarySkillGuide() {}

    static String render(AgentTool tool, String purpose, String operations) {
        return (
            "Skill package v" +
            VERSION +
            " / " +
            tool.name() +
            "\n" +
            "Purpose: " +
            purpose +
            "\n" +
            "Inputs: exact message/messageId; typed bindings/resolvedInputs; required/optional fields in parameterRules and command schema.\n" +
            "Context: mandatory dialogue.messages, exact pendingQuestion with choices, receipt-backed facts and current review.base. " +
            "Use currentWidgetInventory for keys, roles, bindings, presentation, sizes and per-breakpoint order. Unknowns remain unknown.\n" +
            "Allowed operations: " +
            operations +
            "\n" +
            "Constraints: authorization and immutable reviews/receipts remain server-owned. An unexecuted review edit prepares a new review; " +
            "an executed dashboard requires its current dashboardId/revision and supported UPDATE contract. Never create a copy to implement an update.\n" +
            "Questions: only missing required meaning or unresolved authorized choices; NEED_CONTEXT for available domain metadata. " +
            "Do not turn schema/preparation/provider failures into questions or ask again for supplied facts. Unsupported operations must be disclosed.\n" +
            "Response schema: " +
            tool.name() +
            ":output:1; one step.response outcome, no execution claims.\n" +
            examples(tool) +
            "Acceptance: implement every current requested effect against exact authorized targets; preserve all unrelated data/settings/order; " +
            "pass canonical and registered validation, domain preparation and confirmation. Schema validity alone does not prove intent correctness.\n"
        );
    }

    private static String examples(AgentTool tool) {
        return switch (tool) {
            case
                CREATE_DASHBOARD,
                UPDATE_DASHBOARD_DRAFT -> "Examples (command payload fragments, exact schema supplies required keys):\n" +
                "'Назови Северный маршрут' -> operations=[{op:rename_dashboard,id:name,name:'Северный маршрут'}]; keep every widget.\n" +
                "'Текущие скорость и двери наверх' with inventory current speed=c1, current doors=c2, history speed=h1 -> " +
                "operations=[{op:place_widgets,id:move,targets:[c1,c2],position:TOP,anchor:null}]; h1 is untouched.\n" +
                "'Сделай график дверей линией' -> set_presentation for the history key only; no move/resize of current doors.\n" +
                "'Карту ниже' on accepted review -> place_widgets for that exact map key, never show_map with a new key.\n";
            case
                CREATE_DEVICE,
                CREATE_DEMO_DEVICE -> "Examples: 'Создай датчик типа из каталога' -> PREPARED command using the exact catalogue type, proposed name, optional manufacturer/location omitted. " +
                "Short reply 'Кухня' to a location question -> use its exact authorized choice binding; retain the prior device name/type.\n";
            case CREATE_DATA_SOURCE -> "Examples: 'Подключи поддерживаемый источник к выбранному устройству' -> obtain the installed connector contract and propose only its fields. " +
                "A connector missing from that contract -> UNSUPPORTED, never an invented protocol or credential request.\n";
            default -> "Examples: a request within this registered purpose uses the current authorized resource and command parameters. " +
                "A short answer binds only the exact pending field; unavailable evidence uses NEED_CONTEXT, never invented IDs or readings.\n";
        };
    }
}
