package com.voltcanary.aiagent.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/** Wire-only projection; canonical requests, review payloads and validation remain unchanged. */
public final class PrimaryPromptProjection {
    public static final String VERSION = "primary-prompt-v4";
    public static final String GUIDANCE = "In workflow.context, a data choice with viewsRef uses the exact views array "
            + "from dashboardViewSets[viewsRef] in that same context. Other choice metadata remains specific to that dataRef. "
            + "In review.base, currentWidgetInventory is a key/role/size/order index; join key to definition.widgets.id "
            + "for exact type, title, binding, query, presentation, settings and other widget properties. "
            + "widgetCapabilityMatches is advisory retrieval, not permission or proof of user intent. "
            + "The complete dashboardWidgetTypes index retains all compatibility, bindings, units and limits. "
            + "For each capabilities.settingRefs[key], read dashboardWidgetParameters[ref].contract and .schema "
            + "for its exact type, labels, default, choices and constraints; settingsEnvelope contains the object-level schema. "
            + "Search matches.parameterRef points to the same dictionary. Legacy unprojected entries keep settingsSchema/settingContracts. "
            + "Repeated workflow loads use dashboardWidgetTypesRef/dashboardWidgetParametersRef as JSON Pointer paths to the same exact tables in this request. "
            + "A search miss does not make a capability unsupported. changeRequest *Ref fields are exact paths in this request, not missing context.";
    private static final Set<String> DASHBOARD_TOOLS = Set.of("CREATE_DASHBOARD", "UPDATE_DASHBOARD_DRAFT");

    private PrimaryPromptProjection() { }

    public static void changeContext(ObjectNode request) {
        if (!request.path("kind").asText().equals("step.request")) return;
        var change = request.putObject("changeRequest");
        change.set("sourceMessageId", request.path("messageId").deepCopy());
        change.set("text", request.path("message").deepCopy());
        change.put("retainedGoalsRef", "/context/goals");
        change.put("pendingQuestionRef", "/context/dialogue/pendingQuestion");
        change.put("resolvedInputsRef", "/contract/resolvedInputs");
        int loadedIndex = -1;
        for (var loaded : request.path("loadResults")) {
            loadedIndex++;
            if (!loaded.path("loaderId").asText().equals("review.base")) continue;
            change.set("baseReviewId", loaded.at("/data/reviewId").deepCopy());
            int actionIndex = -1;
            for (var action : loaded.at("/data/actions")) {
                actionIndex++;
                if (!DASHBOARD_TOOLS.contains(action.path("tool").asText())) continue;
                change.set("acceptedName", action.at("/parameters/definition/name").deepCopy());
                change.put("widgetInventoryRef", "/loadResults/" + loadedIndex + "/data/actions/" + actionIndex + "/currentWidgetInventory");
            }
        }
    }

    public static JsonNode skillContract(JsonNode contract) {
        var result = contract.deepCopy();
        if (result instanceof ObjectNode object) {
            object.remove(List.of("commandSchema", "bindings", "resolvedInputs", "trustPolicy"));
            // A loader's input contract is needed to request it; its output schema is server validation metadata.
            for (var loader : object.path("loaders")) {
                if (loader instanceof ObjectNode value) value.remove("resultSchema");
            }
        }
        return result;
    }

    public static void workflowCatalogues(ObjectNode request) {
        for (var loaded : request.path("loadResults")) {
            if (!loaded.path("loaderId").asText().equals("workflow.context")
                    || !(loaded.at("/data/context") instanceof ObjectNode context)
                    || context.has("dashboardViewSets")) continue;
            var counts = new LinkedHashMap<JsonNode, Integer>();
            for (var choice : context.path("dashboardDataChoices")) {
                if (choice.path("views").isArray()) counts.merge(choice.path("views"), 1, Integer::sum);
            }
            var refs = new LinkedHashMap<JsonNode, String>();
            for (var entry : counts.entrySet()) {
                if (entry.getValue() > 1 && !entry.getKey().isEmpty()) {
                    refs.put(entry.getKey(), "views" + (refs.size() + 1));
                }
            }
            if (refs.isEmpty()) continue;
            var sets = context.putObject("dashboardViewSets");
            refs.forEach((views, ref) -> sets.set(ref, views.deepCopy()));
            for (var choice : context.path("dashboardDataChoices")) {
                String ref = refs.get(choice.path("views"));
                if (ref != null && choice instanceof ObjectNode object) {
                    object.remove("views");
                    object.put("viewsRef", ref);
                }
            }
        }
    }

    public static void dashboardBase(ObjectNode request) {
        boolean acceptedDefinition = false;
        for (var loaded : request.path("loadResults")) {
            if (!loaded.path("loaderId").asText().equals("review.base")) continue;
            for (var action : loaded.at("/data/actions")) {
                if (!DASHBOARD_TOOLS.contains(action.path("tool").asText())
                        || !action.at("/parameters/definition").isObject()) continue;
                // The compiled accepted definition is the authoritative snapshot, not its historical generator inputs.
                ((ObjectNode) action.path("parameters")).retain("definition");
                if (action.path("currentWidgetInventory") instanceof ObjectNode inventory) {
                    for (var widget : inventory.path("widgets")) {
                        if (widget instanceof ObjectNode entry) {
                            // These properties are present verbatim in definition.widgets, keyed by id.
                            entry.remove(List.of("type", "title", "binding", "query", "presentation", "settings"));
                        }
                    }
                }
                acceptedDefinition |= request.at("/context/baseReviewId").isTextual()
                        && request.at("/context/baseReviewId").equals(loaded.at("/data/reviewId"));
            }
        }
        if (!acceptedDefinition) return;
        for (var loaded : request.path("loadResults")) {
            if (loaded.path("loaderId").asText().equals("workflow.context")
                    && loaded.at("/data/context") instanceof ObjectNode context) {
                context.remove("previousDashboardIntent");
            }
        }
    }
}
