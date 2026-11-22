package com.voltcanary.aiagent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;

/** Focused, versioned skill instructions. Examples are complete responses validated in offline tests. */
public final class DashboardSkillGuide {

    public static final String VERSION = "dashboard-skills-v16";

    private DashboardSkillGuide() {}

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
            ? "No widgetKey field is allowed in this creation response. show_map/show_current/show_history create widgets from supplied dataRef. " +
              "The server assigns persistent widget keys. id names an operation only, not a widget. " +
              "Later operations reference its result as @id; references must point backward to an earlier show/add operation. " +
              "rename_widget uses target=@id. Include one rename_dashboard: exact supplied name or a short name in the user's language (80 characters max). " +
              "Choose every requested current/history/map role and semantic order in this same response; server packing owns coordinates. " +
              "Do not emit existing-widget edits, remove, keep or restore in initial creation."
            : "Existing widgetKey/targets/anchor must use exact currentWidgetInventory keys, never titles, roles or operation IDs. " +
              "show_* with widgetKey=null may add/ensure a requested role; a non-null widgetKey must be an existing matching role/data. " +
              "Use set_data for an explicit data replacement and set_presentation for supported setting changes. " +
              "For each property change resolve its own targets: changing a history view does not authorize moving current cards. " +
              "When a request names widgets after a positional description, use their explicit roles and fields even if their existing positions differ. " +
              "Use CURRENT for current values even if a HISTORY widget has the same title and field. " +
              "For a shared move and resize, use the same resolved keys in both operations. " +
              "For a dashboard-only rename, emit one rename_dashboard operation with exactly op, id and name. " +
              "TOP targets only widgets explicitly requested at the top. A request to show a history chart leaves its position unchanged unless movement is requested for it. " +
              (intent.equals(DashboardSkillContract.REFINE)
                  ? "Use restore_review only for exact saved review ancestry and keep_review only for an explicit request to retain the exact accepted variant."
                  : "Use the current versioned draft target supplied by the server; a conflict requires a new review. Use restore_review for exact previous saved versions of this dashboard.");
        var text = new StringBuilder(
            VERSION + " / " + intent + "\nPurpose: " + purpose(intent) + "\n"
        );
        text.append(DashboardDesignGuide.text());
        text.append(
            "Inputs: exact message/messageId; source-linked pending goals; typed resource bindings. Interpret name, period and widgets from the actual dialogue, not invented planner parameters. " +
                "If workflow.context.requestedDashboardName is present, copy it verbatim to rename_dashboard.name. It is the new name from the current user message.\n"
        );
        text.append(
            "Context: exact pending question/options and mandatory history, receipt-backed facts; workflow.context supplies devices, sources, dataRef choices/views and supported settings. "
        );
        text.append(
            creation
                ? "No existing widget inventory is available in initial creation.\n"
                : "review.base supplies the accepted definition ONCE: definition.widgets contains exact id, type, title, binding, query, presentation, settings and any navigation/payload; definition.layouts contains actual geometry. " +
                      "currentWidgetInventory is its key/role/semantic-size/breakpoint-order index; join key to definition.widgets.id for properties. Historical prepareIntent is not the current dashboard.\n"
        );
        text.append("Allowed operations and preservation: ").append(rules).append('\n');
        if (creation) text.append(
            "On the model wire, PREPARED.command.payload.dashboardPeriod is mandatory and controls the whole dashboard. " +
                "An explicit global period belongs there, independently of show_history.period. Without a user global choice use LAST_24_HOURS. " +
                "The server inserts global set_period before widget operations; canonical reviewed payloads keep operations only.\n"
        );
        else text.append(
            "Data replacement: retain the widget's exact type and visualization. A metric and a recorded source field with the same key are not interchangeable. " +
                "Select dataRef from the schema variant paired with that widgetKey. A compatible field for vehicle-value is not a compatible field for latest-value.\n"
        );
        text.append(
            "Period scope: global set_period uses targets=null or []; a targeted set_period or show_history.period affects only those widgets. " +
                "A global user period must not be implemented only on one chart.\n"
        );
        text.append(
            "Visibility settings are independent: showTitle is the title, showDetails is widget details, " +
                "showMapDetails is map controls, showMapNotices is notices below the map. " +
                "For hiding only map notices, emit set_presentation with settings:[{\"key\":\"showMapNotices\",\"value\":false}]; omit other settings, including mapFields.\n"
        );
        text.append(
            "Constraints: current cards and history charts for one field are different roles. Use only supplied compatible dataRef/views/settings. Boolean history supports line; text history uses list. "
        );
        text.append(
            "Never invent observations, source connections, technical IDs or geometry. No execution claims before an actual receipt.\n"
        );
        text.append(
            "Question conditions: QUESTION only for missing user meaning, using declared fields and real choices. Never re-ask a supplied name; optional naming can be proposed. "
        );
        if (intent.equals(DashboardSkillContract.CREATE)) {
            text.append(
                "An explicit request to ask for the name before preparing a review makes the name required. " +
                    "If no name has been supplied, return outcome QUESTION with missingFields [\"name\"] and choices []; omit command. " +
                    "If contract.resolvedInputs.name is present, preserve it exactly in rename_dashboard. "
            );
        }
        text.append(
            "Use device.catalogue or workflow.context for missing domain data and conversation.history for omitted earlier dialogue. Technical failures are not questions.\n"
        );
        text.append("Response schema: ")
            .append(intent)
            .append(":output:2, command.schemaId='")
            .append(intent)
            .append(":command:2', command.schemaVersion='2'. Emit exactly one outcome.\n");
        text.append(
            "Within operations, choose the schema variant for each op and include only that variant's properties. " +
                "Allocate a unique id to every operation, including placement, renaming and presentation operations. " +
                "For example, show_current id=add_speed and rename_widget id=title_speed are different operations; " +
                "the rename targets @add_speed. Before returning, check that no two operations share an id.\n"
        );
        text.append(
            "Examples: these input/dataRef/key names are illustrative only; actual responses must use the current snapshot.\n"
        );
        for (var example : examples(mapper, intent)) {
            var response = example.path("response").deepCopy();
            if (creation) (
                (com.fasterxml.jackson.databind.node.ObjectNode) response.at("/command/payload")
            ).put("dashboardPeriod", DashboardOperationProtocol.DEFAULT_PERIOD);
            text.append(example.path("request").asText())
                .append("\n")
                .append(response)
                .append('\n');
        }
        if (creation) text.append(
            "User asks for current speed and requests a name question before review; no name is known.\n"
        )
            .append(
                "{\"version\":\"assistant-v2\",\"kind\":\"step.response\",\"outcome\":\"QUESTION\","
            )
            .append("\"explanation\":\"A name is required before review.\",\"question\":")
            .append(
                "{\"text\":\"What should I name the dashboard?\",\"missingFields\":[\"name\"],\"choices\":[]}}\n"
            );
        text.append(
            creation
                ? "Rejected counterexample: show_current with id=speed and widgetKey=speed is invalid, even widgetKey=null is forbidden here. Correct creation: omit widgetKey entirely.\n"
                : "Rejected counterexample: target=CURRENT_SPEED absent from inventory is UNKNOWN_WIDGET_KEY, not permission to create a replacement. @future before its creating operation is invalid.\n"
        );
        text.append(
            "Cover every effect in changeRequest and retained goals, including quantifiers. Preserve unrelated roles, bindings, settings and order. " +
                "Disclose unsupported effects. Use NO_CHANGE/keep only when it fulfills the request.\n"
        );
        return text.toString();
    }

    public static List<JsonNode> examples(ObjectMapper mapper, String intent) {
        var result = new ArrayList<JsonNode>();
        if (intent.equals(DashboardSkillContract.CREATE)) {
            result.add(
                example(
                    mapper,
                    intent,
                    "Создай «Поездка» с текущей скоростью. Available dataRef: speed_ref.",
                    """
                    [{"op":"rename_dashboard","id":"name","name":"Поездка"},
                     {"op":"show_current","id":"speed","dataRef":"speed_ref"}]
                    """
                )
            );
            result.add(
                example(
                    mapper,
                    intent,
                    "Создай «Маршрут»: карту и график скорости, карточку скорости поставь сверху. Available dataRef: position_ref, speed_ref.",
                    """
                    [{"op":"rename_dashboard","id":"name","name":"Маршрут"},
                     {"op":"show_map","id":"map","dataRef":"position_ref"},
                     {"op":"show_history","id":"history","dataRef":"speed_ref","view":"line","period":null},
                     {"op":"show_current","id":"speed","dataRef":"speed_ref"},
                     {"op":"rename_widget","id":"title","target":"@speed","title":"Скорость сейчас"},
                     {"op":"place_widgets","id":"top","targets":["@speed"],"position":"TOP","anchor":null}]
                    """
                )
            );
        } else {
            result.add(
                example(
                    mapper,
                    intent,
                    "Назови «Поездка домой», остальное сохрани.",
                    """
                    [{"op":"rename_dashboard","id":"name","name":"Поездка домой"}]
                    """
                )
            );
            result.add(
                example(
                    mapper,
                    intent,
                    "Move the temperature card up and add current humidity beside it. Inventory: temp_now and humidity_history. Current dataRef: humidity_ref.",
                    """
                    [{"op":"show_current","id":"humidity_card","dataRef":"humidity_ref","widgetKey":null},
                     {"op":"place_widgets","id":"top","targets":["temp_now","@humidity_card"],"position":"TOP","anchor":null}]
                    """
                )
            );
        }
        return List.copyOf(result);
    }

    private static JsonNode example(
        ObjectMapper mapper,
        String intent,
        String input,
        String operations
    ) {
        try {
            var result = mapper.createObjectNode().put("request", input);
            var response = result
                .putObject("response")
                .put("version", "assistant-v2")
                .put("kind", "step.response")
                .put("outcome", "PREPARED")
                .put("explanation", "Подготовлен вариант для проверки.");
            var payload = response
                .putObject("command")
                .put("schemaId", intent + ":command:2")
                .put("schemaVersion", "2")
                .putObject("payload")
                .put("protocol", DashboardOperationProtocol.VERSION);
            payload.set("operations", mapper.readTree(operations));
            return result;
        } catch (java.io.IOException invalid) {
            throw new IllegalStateException("INVALID_SERVER_SKILL_EXAMPLE", invalid);
        }
    }
}
