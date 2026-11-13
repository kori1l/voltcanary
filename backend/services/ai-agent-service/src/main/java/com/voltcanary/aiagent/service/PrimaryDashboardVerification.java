package com.voltcanary.aiagent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.aiagent.model.AgentPlan;
import com.voltcanary.aiagent.orchestration.plan.IntentPlans;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Structural postconditions, not a claim that the model correctly interpreted natural language. */
final class PrimaryDashboardVerification {
    private PrimaryDashboardVerification() { }
    static void verify(ObjectMapper mapper, PlanStore.Stored base, AgentPlan prepared, DashboardOperationCompiler.Result expected,
            DashboardOperationProtocol.Envelope operations, AuthorizedPlanningContext.Prepared context) {
        var action = mapper.valueToTree(prepared.actions().getFirst().parameters());
        var actual = action.path("prepareIntent");
        var definition = action.path("definition");
        if (!actual.path("name").asText().equals(definition.path("name").asText()) || actual.path("name").asText().isBlank()) fail("DASHBOARD_NAME_POSTCONDITION");
        if (!actual.path("period").isTextual() || !actual.path("period").equals(definition.at("/defaultContext/time/preset")))
            fail("DASHBOARD_PERIOD_POSTCONDITION");
        var widgets = indexed(actual.path("widgets"), "key");
        var defined = indexed(definition.path("widgets"), "id");
        if (!widgets.keySet().equals(defined.keySet()) || widgets.size() != ((List<?>) expected.intent().get("widgets")).size()) fail("WIDGET_IDENTITY_POSTCONDITION");
        widgets.forEach((id, widget) -> {
            if (!widget.path("type").equals(defined.get(id).path("type"))) fail("WIDGET_TYPE_POSTCONDITION");
            if (widget.has("title") && !widget.path("title").equals(defined.get(id).path("title"))) fail("WIDGET_TITLE_POSTCONDITION");
        });
        if (base != null) {
            var prior = mapper.valueToTree(base.plan().actions().getFirst().parameters()).path("prepareIntent");
            var previous = indexed(prior.path("widgets"), "key");
            previous.forEach((id, old) -> {
                Set<String> mask = expected.allowedChanges().getOrDefault(id, Set.of());
                if (!widgets.containsKey(id)) {
                    if (!mask.contains("removed")) fail("UNEXPECTED_WIDGET_REMOVAL");
                    return;
                }
                if (mask.contains("removed")) fail("REMOVAL_POSTCONDITION");
                var next = widgets.get(id);
                var fields = new HashSet<String>();
                old.fieldNames().forEachRemaining(fields::add);
                next.fieldNames().forEachRemaining(fields::add);
                for (String field : fields) {
                    if (field.equals("settings")) {
                        var settings = new HashSet<String>();
                        old.path(field).fieldNames().forEachRemaining(settings::add);
                        next.path(field).fieldNames().forEachRemaining(settings::add);
                        for (String key : settings) if (!mask.contains("settings." + key) && !old.path(field).path(key).equals(next.path(field).path(key))) fail("UNTARGETED_SETTING_CHANGED");
                    } else if (!mask.contains(field) && !field.equals("dataChoiceId") && !old.path(field).equals(next.path(field))) fail("UNTARGETED_WIDGET_FIELD_CHANGED");
                }
            });
            Set<String> root = expected.allowedChanges().getOrDefault("$dashboard", Set.of());
            for (String field : List.of("name", "period")) if (!root.contains(field) && !prior.path(field).equals(actual.path(field))) fail("UNTARGETED_DASHBOARD_FIELD_CHANGED");
            var geometric = new HashSet<String>();
            expected.allowedChanges().forEach((id, fields) -> {
                if (fields.stream().anyMatch(Set.of("order", "width", "height", "created", "removed")::contains)) geometric.add(id);
            });
            verifyLayouts(mapper.valueToTree(base.plan().actions().getFirst().parameters()).path("definition"), definition, geometric);
        }
        // Check explicit targeted values independently of compiler-generated masks.
        var envelope = mapper.valueToTree(operations);
        for (var operation : envelope.path("operations")) {
            String op = operation.path("op").asText();
            if (op.equals("rename_dashboard") && !operation.path("name").equals(actual.path("name"))) fail("RENAME_POSTCONDITION");
            for (var target : operation.path("targets")) {
                if (target.asText().startsWith("@") || target.asText().equals("ALL")) continue;
                var widget = widgets.get(target.asText());
                if (op.equals("remove_widgets")) {
                    if (widget != null) fail("REMOVAL_POSTCONDITION");
                } else if (widget == null) fail("TARGET_POSTCONDITION");
                else if (op.equals("resize_widgets") && operation.hasNonNull("width") && !operation.path("width").equals(widget.path("width"))) fail("WIDTH_POSTCONDITION");
            }
        }
        var layouts = definition.path("layouts");
        layouts.fields().forEachRemaining(entry -> {
            if (!entry.getValue().isArray()) return;
            var rectangles = new java.util.ArrayList<JsonNode>();
            for (var rectangle : entry.getValue()) {
                if (rectangle.path("w").asInt() <= 0 || rectangle.path("h").asInt() <= 0) fail("INVALID_LAYOUT_GEOMETRY");
                for (var other : rectangles) if (rectangle.path("x").asInt() < other.path("x").asInt() + other.path("w").asInt()
                        && other.path("x").asInt() < rectangle.path("x").asInt() + rectangle.path("w").asInt()
                        && rectangle.path("y").asInt() < other.path("y").asInt() + other.path("h").asInt()
                        && other.path("y").asInt() < rectangle.path("y").asInt() + rectangle.path("h").asInt()) fail("OVERLAPPING_LAYOUT");
                rectangles.add(rectangle);
            }
        });
    }
    static void verifyLayouts(JsonNode previous, JsonNode current, Set<String> geometricTargets) {
        if (geometricTargets.isEmpty() && !previous.path("layouts").equals(current.path("layouts"))) fail("UNTARGETED_LAYOUT_CHANGED");
        previous.path("layouts").fields().forEachRemaining(entry -> {
            var next = current.path("layouts").path(entry.getKey());
            if (!entry.getValue().isArray() || !next.isArray()) fail("LAYOUT_BREAKPOINT_MISSING");
            var before = ordered(entry.getValue()).stream().filter(id -> !geometricTargets.contains(id)).toList();
            var after = ordered(next).stream().filter(before::contains).toList();
            if (!before.equals(after)) fail("UNTARGETED_LAYOUT_ORDER_CHANGED");
        });
    }
    private static List<String> ordered(JsonNode layout) {
        var items = new java.util.ArrayList<JsonNode>();
        layout.forEach(items::add);
        items.sort(java.util.Comparator.comparingInt((JsonNode item) -> item.path("y").asInt()).thenComparingInt(item -> item.path("x").asInt()));
        return items.stream().map(item -> item.path("widgetId").asText()).toList();
    }
    private static Map<String, JsonNode> indexed(JsonNode array, String field) {
        var result = new HashMap<String, JsonNode>();
        if (!array.isArray()) fail("WIDGETS_REQUIRED");
        for (var widget : array) if (widget.path(field).asText().isBlank() || result.putIfAbsent(widget.path(field).asText(), widget) != null) fail("DUPLICATE_WIDGET_IDENTITY");
        return result;
    }
    private static void fail(String code) { throw new com.voltcanary.aiagent.orchestration.plan.StepPreparationFailure(
            new com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.Diagnostic("OUTCOME", code, "Подготовленный вариант нарушает проверяемое условие: " + code, List.of(), true)); }
}
