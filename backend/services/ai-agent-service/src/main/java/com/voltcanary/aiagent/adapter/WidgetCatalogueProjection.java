package com.voltcanary.aiagent.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.List;

/** Lossless wire interning. Search chooses references; it cannot remove a supported setting. */
public final class WidgetCatalogueProjection {
    public static final String VERSION = "widget-catalogue-wire-v2";
    private WidgetCatalogueProjection() { }

    public static void compactRequest(ObjectNode request, ObjectMapper mapper) {
        var bundles = new LinkedHashMap<JsonNode, String>();
        int index = -1;
        for (var loaded : request.path("loadResults")) {
            index++;
            if (!loaded.path("loaderId").asText().equals("workflow.context")
                    || !(loaded.at("/data/context") instanceof ObjectNode context)) continue;
            compact(context, mapper);
            if (!context.path("dashboardWidgetTypes").isArray() || !context.path("dashboardWidgetParameters").isObject()) continue;
            var bundle = mapper.createObjectNode();
            bundle.set("catalogue", context.path("dashboardWidgetTypes"));
            bundle.set("parameters", context.path("dashboardWidgetParameters"));
            String path = "/loadResults/" + index + "/data/context";
            String existing = bundles.putIfAbsent(bundle, path);
            if (existing != null) {
                context.remove(List.of("dashboardWidgetTypes", "dashboardWidgetParameters"));
                context.put("dashboardWidgetTypesRef", existing + "/dashboardWidgetTypes");
                context.put("dashboardWidgetParametersRef", existing + "/dashboardWidgetParameters");
            }
        }
    }

    public static void compact(ObjectNode context, ObjectMapper mapper) {
        if (!context.path("dashboardWidgetTypes").isArray() || context.has("dashboardWidgetParameters")) return;
        var dictionary = mapper.createObjectNode();
        var ids = new LinkedHashMap<JsonNode, String>();
        var catalogue = context.path("dashboardWidgetTypes").deepCopy();
        long before = catalogue.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        for (var widget : catalogue) {
            if (!(widget.path("capabilities") instanceof ObjectNode capability)
                    || !capability.path("settingContracts").isArray() || !capability.path("settingsSchema").isObject()) continue;
            var references = mapper.createObjectNode();
            boolean complete = true;
            for (var setting : capability.path("settingContracts")) {
                String key = setting.path("key").asText();
                if (key.isBlank() || !capability.at("/settingsSchema/properties").has(key) || references.has(key)) {
                    complete = false;
                    break;
                }
                var parameter = mapper.createObjectNode();
                parameter.set("contract", setting.deepCopy());
                parameter.set("schema", capability.at("/settingsSchema/properties").path(key).deepCopy());
                if (capability.path("settings").has(key)) parameter.set("hint", capability.path("settings").path(key).deepCopy());
                String id = ids.get(parameter);
                if (id == null) {
                    id = "parameter" + ids.size();
                    ids.put(parameter, id);
                    dictionary.set(id, parameter);
                }
                references.put(key, id);
            }
            if (!complete || references.size() != capability.at("/settingsSchema/properties").size()
                    || capability.path("settings").isObject() && capability.path("settings").size() != references.size()) continue;
            var envelope = ((ObjectNode) capability.path("settingsSchema")).deepCopy();
            envelope.remove("properties");
            capability.remove(List.of("settings", "settingContracts", "settingsSchema"));
            capability.set("settingRefs", references);
            capability.set("settingsEnvelope", envelope);
        }
        if (dictionary.isEmpty()) return;
        long after = (catalogue.toString() + dictionary).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        // Include the dictionary field name and bounded projection metadata in the saving threshold.
        if (after + 256 >= before) return;
        context.set("dashboardWidgetTypes", catalogue);
        context.set("dashboardWidgetParameters", dictionary);
        for (var match : context.at("/widgetCapabilityMatches/matches")) {
            if (!(match instanceof ObjectNode object) || !match.has("settingKey")) continue;
            for (var widget : catalogue) {
                if (widget.path("type").equals(match.path("widgetType"))) {
                    var reference = widget.at("/capabilities/settingRefs").path(match.path("settingKey").asText());
                    if (reference.isTextual()) object.set("parameterRef", reference.deepCopy());
                }
            }
        }
        context.putObject("widgetCatalogueProjection").put("version", VERSION).put("originalBytes", before)
                .put("projectedBytes", after).put("complete", true);
    }
}
