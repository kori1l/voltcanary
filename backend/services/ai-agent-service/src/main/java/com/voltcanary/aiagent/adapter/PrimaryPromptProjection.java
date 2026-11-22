package com.voltcanary.aiagent.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/** Wire-only projection; canonical requests, review payloads and validation remain unchanged. */
public final class PrimaryPromptProjection {

    public static final String VERSION = "primary-prompt-v11";
    public static final String GUIDANCE =
        "In workflow.context, a data choice with viewsRef uses the exact views array " +
        "from dashboardViewSets[viewsRef] in that same context. Other choice metadata remains specific to that dataRef. " +
        "In review.base, match both role and fieldKey in currentWidgetInventory when selecting widget keys; join key to definition.widgets.id " +
        "for exact type, title, binding, query, presentation, settings and other widget properties. " +
        "Use changeRequest.widgetKeysByRoleAndField to resolve explicitly named current or history targets before considering their layout positions. " +
        "Use changeRequest.widgetOrderRef for the existing order at each breakpoint. For a partial move, pass only the moved widget keys; the server retains the relative order of every other key. " +
        "widgetCapabilityMatches is advisory retrieval, not permission or proof of user intent. " +
        "The complete dashboardWidgetTypes index retains all compatibility, bindings, units and limits. " +
        "For each capabilities.settingRefs[key], read dashboardWidgetParameters[ref].contract and .schema " +
        "for its exact type, labels, default, choices and constraints; settingsEnvelope contains the object-level schema. " +
        "Search matches.parameterRef points to the same dictionary. Legacy unprojected entries keep settingsSchema/settingContracts. " +
        "Repeated workflow loads use dashboardWidgetTypesRef/dashboardWidgetParametersRef as JSON Pointer paths to the same exact tables in this request. " +
        "A search miss does not make a capability unsupported. changeRequest *Ref fields are exact paths in this request, not missing context. " +
        "Use changeRequest.retainedRequests for the exact earlier user requests that still have pending goals. " +
        "Resolve each requested effect and target against those requests and changeRequest.text before writing operations. " +
        "Treat a request to show or restyle a widget separately from a request to move it. " +
        "For a dashboard rename, changeRequest.requestedName is the exact new name; acceptedName is the old name.";
    private static final Set<String> DASHBOARD_TOOLS = Set.of(
        "CREATE_DASHBOARD",
        "UPDATE_DASHBOARD_DRAFT"
    );

    private PrimaryPromptProjection() {}

    public static void changeContext(ObjectNode request) {
        if (!request.path("kind").asText().equals("step.request")) return;
        var change = request.putObject("changeRequest");
        change.set("sourceMessageId", request.path("messageId").deepCopy());
        change.set("text", request.path("message").deepCopy());
        change.put("retainedGoalsRef", "/context/goals");
        change.put("pendingQuestionRef", "/context/dialogue/pendingQuestion");
        change.put("resolvedInputsRef", "/contract/resolvedInputs");
        var sources = new LinkedHashMap<String, String>();
        int messageIndex = 0;
        for (var message : request.at("/context/dialogue/messages")) {
            if (
                "USER".equals(message.path("role").asText()) &&
                message.path("messageId").isTextual()
            ) {
                sources.put(
                    message.path("messageId").textValue(),
                    "/context/dialogue/messages/" + messageIndex
                );
            }
            messageIndex++;
        }
        var goalSources = request.objectNode();
        var retainedRequests = new LinkedHashMap<String, ObjectNode>();
        for (var goal : request.at("/context/goals")) {
            if (!"PENDING".equals(goal.path("disposition").asText())) continue;
            String source = goal.path("sourceMessageId").asText();
            String pointer = request.path("messageId").asText().equals(source)
                ? "/changeRequest"
                : sources.get(source);
            if (pointer == null) continue;
            String goalId = goal.path("goalId").asText();
            goalSources.put(goalId, pointer);
            if (
                pointer.equals("/changeRequest") || !request.at(pointer).path("text").isTextual()
            ) continue;
            var retained = retainedRequests.computeIfAbsent(source, id -> {
                var entry = request.objectNode().put("sourceMessageId", id);
                entry.set("text", request.at(pointer).path("text").deepCopy());
                return entry;
            });
            retained.withArray("goalIds").add(goalId);
        }
        if (!goalSources.isEmpty()) change.set("pendingGoalSources", goalSources);
        if (!retainedRequests.isEmpty()) {
            var retained = change.putArray("retainedRequests");
            retainedRequests.values().forEach(retained::add);
        }
        int loadedIndex = -1;
        for (var loaded : request.path("loadResults")) {
            loadedIndex++;
            if (loaded.path("loaderId").asText().equals("workflow.context")) {
                var requestedName = loaded.at("/data/context/requestedDashboardName");
                if (requestedName.isTextual()) change.set(
                    "requestedName",
                    requestedName.deepCopy()
                );
            }
            if (!loaded.path("loaderId").asText().equals("review.base")) continue;
            change.set("baseReviewId", loaded.at("/data/reviewId").deepCopy());
            int actionIndex = -1;
            for (var action : loaded.at("/data/actions")) {
                actionIndex++;
                if (!DASHBOARD_TOOLS.contains(action.path("tool").asText())) continue;
                change.set("acceptedName", action.at("/parameters/definition/name").deepCopy());
                change.put(
                    "widgetInventoryRef",
                    "/loadResults/" +
                        loadedIndex +
                        "/data/actions/" +
                        actionIndex +
                        "/currentWidgetInventory"
                );
                if (action.at("/currentWidgetInventory/order").isObject()) {
                    change.put(
                        "widgetOrderRef",
                        change.path("widgetInventoryRef").asText() + "/order"
                    );
                }
                var index = request.objectNode();
                for (var widget : action.at("/currentWidgetInventory/widgets")) {
                    String role = widget.path("role").asText();
                    var field = widget.path("fieldKey");
                    var key = widget.path("key");
                    if (
                        (!role.equals("CURRENT") && !role.equals("HISTORY")) ||
                        !field.isTextual() ||
                        !key.isTextual()
                    ) continue;
                    index
                        .withObjectProperty(role)
                        .withArrayProperty(field.textValue())
                        .add(key.textValue());
                }
                if (!index.isEmpty()) change.set("widgetKeysByRoleAndField", index);
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
            if (
                !loaded.path("loaderId").asText().equals("workflow.context") ||
                !(loaded.at("/data/context") instanceof ObjectNode context) ||
                context.has("dashboardViewSets")
            ) continue;
            var counts = new LinkedHashMap<JsonNode, Integer>();
            for (var choice : context.path("dashboardDataChoices")) {
                if (choice.path("views").isArray()) counts.merge(
                    choice.path("views"),
                    1,
                    Integer::sum
                );
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
                if (
                    !DASHBOARD_TOOLS.contains(action.path("tool").asText()) ||
                    !action.at("/parameters/definition").isObject()
                ) continue;
                // The compiled accepted definition is the authoritative snapshot, not its historical generator inputs.
                ((ObjectNode) action.path("parameters")).retain("definition");
                if (action.path("currentWidgetInventory") instanceof ObjectNode inventory) {
                    for (var widget : inventory.path("widgets")) {
                        if (widget instanceof ObjectNode entry) {
                            // These properties are present verbatim in definition.widgets, keyed by id.
                            entry.remove(
                                List.of(
                                    "type",
                                    "title",
                                    "binding",
                                    "query",
                                    "presentation",
                                    "settings"
                                )
                            );
                        }
                    }
                }
                acceptedDefinition |=
                    request.at("/context/baseReviewId").isTextual() &&
                    request.at("/context/baseReviewId").equals(loaded.at("/data/reviewId"));
            }
        }
        if (!acceptedDefinition) return;
        for (var loaded : request.path("loadResults")) {
            if (
                loaded.path("loaderId").asText().equals("workflow.context") &&
                loaded.at("/data/context") instanceof ObjectNode context
            ) {
                context.remove("previousDashboardIntent");
            }
        }
    }
}
