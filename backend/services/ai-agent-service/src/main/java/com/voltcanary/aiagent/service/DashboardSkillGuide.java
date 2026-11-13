package com.voltcanary.aiagent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;

/** Focused, versioned skill instructions. Examples are complete responses validated in offline tests. */
public final class DashboardSkillGuide {
    public static final String VERSION = "dashboard-skills-v6";
    private DashboardSkillGuide() { }

    public static String purpose(String intent) {
        return switch (intent) {
            case DashboardSkillContract.CREATE -> "Create ONE new dashboard from actual device/source capabilities. No accepted dashboard base; receipt-backed device reviews are allowed as inputs. Planner supplies resource bindings only; this skill selects widgets, name, period and semantic order.";
            case DashboardSkillContract.REFINE -> "Refine the selected UNEXECUTED dashboard review. Preserve its widgets and exact bindings except explicit requested changes. Prepare another immutable review; do not create/update a dashboard before confirmation.";
            case DashboardSkillContract.UPDATE -> "Update the SAME already-created dashboard draft. Use the server-loaded dashboardId/etag/baseRevision from its executed review, current authorized capabilities and a new confirmation. Never create a copy.";
            default -> throw new IllegalArgumentException("UNKNOWN_DASHBOARD_SKILL");
        };
    }

    public static String render(ObjectMapper mapper, String intent) {
        boolean creation = intent.equals(DashboardSkillContract.CREATE);
        String rules = creation
                ? "No widgetKey field is allowed in this creation response. show_map/show_current/show_history create widgets from supplied dataRef. "
                    + "The server assigns persistent widget keys. id names an operation only, not a widget. "
                    + "Later operations reference its result as @id; references must point backward to an earlier show/add operation. "
                    + "rename_widget uses target=@id. Include exactly one rename_dashboard; use the user's name or propose a concise name in their language. "
                    + "Choose every requested current/history/map role and semantic order in this same response; server packing owns coordinates. "
                    + "Do not emit existing-widget edits, remove, keep or restore in initial creation."
                : "Existing widgetKey/targets/anchor must use exact currentWidgetInventory keys, never titles, roles or operation IDs. "
                    + "show_* with widgetKey=null may add/ensure a requested role; a non-null widgetKey must be an existing matching role/data. "
                    + "Use set_data for an explicit data replacement and set_presentation for supported setting changes. "
                    + "New additions use local operation ids and later @id references; never convert an unknown existing key into a new widget. "
                    + "For each property change resolve its own targets: changing a history view does not authorize moving current cards. "
                    + "TOP moves only the selected group ahead of the first widget; preserve group order unless specified and preserve all other relative order. "
                    + (intent.equals(DashboardSkillContract.REFINE)
                        ? "Use restore_review only for exact saved review ancestry and keep_review only for an explicit request to retain the exact accepted variant."
                        : "Use the current versioned draft target supplied by the server; a conflict requires a new review. keep/restore of executed drafts is not supported by this skill.");
        var text = new StringBuilder(VERSION + " / " + intent + "\nPurpose: " + purpose(intent) + "\n");
        text.append(DashboardDesignGuide.text());
        text.append("Inputs: exact message/messageId; source-linked pending goals; typed resource bindings. Interpret name, period and widgets from the actual dialogue, not invented planner parameters.\n");
        text.append("Context: exact pending question/options and mandatory history, receipt-backed facts; workflow.context supplies devices, sources, dataRef choices/views and supported settings. ");
        text.append(creation ? "No existing widget inventory is available in initial creation.\n"
                : "review.base supplies the accepted definition ONCE: definition.widgets contains exact id, type, title, binding, query, presentation, settings and any navigation/payload; definition.layouts contains actual geometry. "
                    + "currentWidgetInventory is its key/role/semantic-size/breakpoint-order index; join key to definition.widgets.id for properties. Historical prepareIntent is not the current dashboard.\n");
        text.append("Allowed operations and preservation: ").append(rules).append('\n');
        if (creation) text.append("On the model wire, PREPARED.command.payload.dashboardPeriod is mandatory and controls the whole dashboard. "
                + "An explicit global period belongs there, independently of show_history.period. Without a user global choice use LAST_24_HOURS. "
                + "The server inserts global set_period before widget operations; canonical reviewed payloads keep operations only.\n");
        else text.append("Data replacement: retain the widget's exact type and visualization. A metric and a recorded source field with the same key are not interchangeable. "
                + "Select dataRef from the schema variant paired with that widgetKey. A compatible field for vehicle-value is not a compatible field for latest-value.\n");
        text.append("Period scope: global set_period uses targets=null or []; a targeted set_period or show_history.period affects only those widgets. "
                + "A global user period must not be implemented only on one chart.\n");
        text.append("Visibility properties are independent: showTitle controls the widget title; showDetails controls widget details; "
                + "showMapDetails controls map controls/details; showMapNotices controls the notice/footer block below the map. "
                + "For a request covering several visibility groups, account for every supported requested group. Changing visibility does not change observations or freshness.\n");
        text.append("Constraints: current cards and history charts for one field are different roles. Use only supplied compatible dataRef/views/settings. Boolean history supports line; text history uses list. ");
        text.append("Never invent observations, source connections, technical IDs or geometry. No execution claims before an actual receipt.\n");
        text.append("Question conditions: QUESTION only for missing user meaning, using declared fields and real choices. Never re-ask a supplied name; optional naming can be proposed. ");
        text.append("Use device.catalogue or workflow.context for missing domain data and conversation.history for omitted earlier dialogue. Technical failures are not questions.\n");
        text.append("Response schema: ").append(intent).append(":output:2, command.schemaId='").append(intent)
                .append(":command:2', command.schemaVersion='2'. Emit exactly one outcome.\n");
        text.append("Examples: these input/dataRef/key names are illustrative only; actual responses must use the current snapshot.\n");
        for (var example : examples(mapper, intent)) {
            var response = example.path("response").deepCopy();
            if (creation) ((com.fasterxml.jackson.databind.node.ObjectNode) response.at("/command/payload"))
                    .put("dashboardPeriod", DashboardOperationProtocol.DEFAULT_PERIOD);
            text.append(example.path("request").asText()).append("\n").append(response).append('\n');
        }
        text.append(creation
                ? "Rejected counterexample: show_current with id=speed and widgetKey=speed is invalid, even widgetKey=null is forbidden here. Correct creation: omit widgetKey entirely.\n"
                : "Rejected counterexample: target=CURRENT_SPEED absent from inventory is UNKNOWN_WIDGET_KEY, not permission to create a replacement. @future before its creating operation is invalid.\n");
        text.append("Acceptance: all source requests/pending goals must be covered; preserve unrelated roles, bindings, settings and order. ");
        text.append("changeRequest identifies exact text, accepted name and retained goals. Map every requested effect to exact inventory roles; apply quantifiers/settings to all matches and disclose unsupported effects. "
                + "Never re-ask supplied values. Use unique operation IDs, not widget keys. ");
        text.append("A syntactically valid command or writer-selected mask alone does not establish intent correctness. Do not use NO_CHANGE/keep to hide an unfulfilled request.\n");
        return text.toString();
    }

    public static List<JsonNode> examples(ObjectMapper mapper, String intent) {
        var result = new ArrayList<JsonNode>();
        if (intent.equals(DashboardSkillContract.CREATE)) {
            result.add(example(mapper, intent, "Создай «Поездка» с текущей скоростью. Available dataRef: speed_ref.", """
                    [{"op":"rename_dashboard","id":"name","name":"Поездка"},
                     {"op":"show_current","id":"speed","dataRef":"speed_ref"}]
                    """));
            result.add(example(mapper, intent, "Создай «Маршрут»: карту и график скорости, карточку скорости поставь сверху. Available dataRef: position_ref, speed_ref.", """
                    [{"op":"rename_dashboard","id":"name","name":"Маршрут"},
                     {"op":"show_map","id":"map","dataRef":"position_ref"},
                     {"op":"show_history","id":"history","dataRef":"speed_ref","view":"line","period":null},
                     {"op":"show_current","id":"speed","dataRef":"speed_ref"},
                     {"op":"rename_widget","id":"title","target":"@speed","title":"Скорость сейчас"},
                     {"op":"place_widgets","id":"top","targets":["@speed"],"position":"TOP","anchor":null}]
                    """));
        } else {
            result.add(example(mapper, intent, "Назови «Поездка домой», остальное сохрани.", """
                    [{"op":"rename_dashboard","id":"name","name":"Поездка домой"}]
                    """));
            result.add(example(mapper, intent, "Текущие скорость и двери наверх. Inventory: speed_now and doors_now are CURRENT; speed_history is HISTORY.", """
                    [{"op":"place_widgets","id":"top","targets":["speed_now","doors_now"],"position":"TOP","anchor":null}]
                    """));
        }
        return List.copyOf(result);
    }

    private static JsonNode example(ObjectMapper mapper, String intent, String input, String operations) {
        try {
            var result = mapper.createObjectNode().put("request", input);
            var response = result.putObject("response").put("version", "assistant-v2").put("kind", "step.response")
                    .put("outcome", "PREPARED").put("explanation", "Подготовлен вариант для проверки.");
            var payload = response.putObject("command").put("schemaId", intent + ":command:2").put("schemaVersion", "2")
                    .putObject("payload").put("protocol", DashboardOperationProtocol.VERSION);
            payload.set("operations", mapper.readTree(operations));
            return result;
        } catch (java.io.IOException invalid) { throw new IllegalStateException("INVALID_SERVER_SKILL_EXAMPLE", invalid); }
    }
}
