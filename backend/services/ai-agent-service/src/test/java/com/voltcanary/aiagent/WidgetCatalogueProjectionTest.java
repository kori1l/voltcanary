package com.voltcanary.aiagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voltcanary.aiagent.adapter.WidgetCatalogueProjection;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WidgetCatalogueProjectionTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private ObjectNode context() throws Exception {
        var context = mapper.createObjectNode();
        var widgets = context.putArray("dashboardWidgetTypes");
        for (int i = 0; i < 18; i++) {
            var widget = widgets.addObject().put("type", "type" + i).put("description", "A registered capability");
            var capability = widget.putObject("capabilities").put("dataKind", "ACTUAL_DATA");
            capability.putObject("binding").put("maxDevices", 1);
            capability.putArray("valueTypes").add("boolean");
            capability.set("settings", mapper.readTree("{\"showTitle\":\"BOOLEAN; default=false\",\"compact\":\"AUTO_BOOLEAN; default=automatic\"}"));
            capability.set("settingContracts", mapper.readTree("""
                    [{"key":"showTitle","type":"BOOLEAN","label":"Show panel title","defaultValue":false,"choices":[]},
                     {"key":"compact","type":"AUTO_BOOLEAN","label":"Value card layout","defaultValue":null,"choices":[]}]
                    """));
            capability.set("settingsSchema", mapper.readTree("""
                    {"type":"object","maxProperties":32,"additionalProperties":{"type":"null"},"properties":{
                      "showTitle":{"description":"Show panel title","type":["boolean","null"],"default":false},
                      "compact":{"description":"Value card layout","type":["boolean","null"],"default":null}}}
                    """));
        }
        context.putArray("dashboardDataChoices").addObject().put("dataRef", "owned-current");
        return context;
    }

    private JsonNode expand(ObjectNode context) {
        var expanded = context.path("dashboardWidgetTypes").deepCopy();
        for (var widget : expanded) {
            var capability = (ObjectNode) widget.path("capabilities");
            if (!capability.has("settingRefs")) continue;
            var schema = ((ObjectNode) capability.path("settingsEnvelope")).deepCopy();
            var properties = schema.putObject("properties");
            var contracts = mapper.createArrayNode();
            var hints = mapper.createObjectNode();
            capability.path("settingRefs").fields().forEachRemaining(entry -> {
                var parameter = context.path("dashboardWidgetParameters").path(entry.getValue().asText());
                contracts.add(parameter.path("contract").deepCopy());
                properties.set(entry.getKey(), parameter.path("schema").deepCopy());
                if (parameter.has("hint")) hints.set(entry.getKey(), parameter.path("hint").deepCopy());
            });
            capability.remove(java.util.List.of("settingRefs", "settingsEnvelope"));
            capability.set("settingsSchema", schema);
            capability.set("settingContracts", contracts);
            if (!hints.isEmpty()) capability.set("settings", hints);
        }
        return expanded;
    }

    @Test void completeCatalogueRoundTripsWithDefaultsConstraintsBindingsAndOrder() throws Exception {
        var context = context();
        var before = context.deepCopy();
        int bytes = context.toString().length();
        WidgetCatalogueProjection.compact(context, mapper);
        assertEquals(2, context.path("dashboardWidgetParameters").size());
        assertEquals(before.path("dashboardWidgetTypes"), expand(context));
        assertEquals(before.path("dashboardDataChoices"), context.path("dashboardDataChoices"));
        assertTrue(context.toString().length() < bytes / 2);
        var once = context.deepCopy();
        WidgetCatalogueProjection.compact(context, mapper);
        assertEquals(once, context);
    }

    @Test void sameKeyWithDifferentMeaningOrDefaultNeverSharesAContract() throws Exception {
        var context = context();
        ((ObjectNode) context.at("/dashboardWidgetTypes/1/capabilities/settingContracts/0")).put("defaultValue", true);
        ((ObjectNode) context.at("/dashboardWidgetTypes/1/capabilities/settingsSchema/properties/showTitle")).put("default", true);
        var before = context.deepCopy();
        WidgetCatalogueProjection.compact(context, mapper);
        assertEquals(3, context.path("dashboardWidgetParameters").size());
        assertNotEquals(context.at("/dashboardWidgetTypes/0/capabilities/settingRefs/showTitle"),
                context.at("/dashboardWidgetTypes/1/capabilities/settingRefs/showTitle"));
        assertEquals(before.path("dashboardWidgetTypes"), expand(context));
    }

    @Test void retrievalReferencesDoNotExcludeUnrankedCapabilities() throws Exception {
        var context = context();
        context.putObject("widgetCapabilityMatches").putArray("matches").addObject().put("widgetType", "type0").put("settingKey", "showTitle");
        WidgetCatalogueProjection.compact(context, mapper);
        String ref = context.at("/widgetCapabilityMatches/matches/0/parameterRef").asText();
        assertEquals("showTitle", context.path("dashboardWidgetParameters").path(ref).at("/contract/key").asText());
        assertEquals(18, context.path("dashboardWidgetTypes").size());
        assertTrue(context.at("/dashboardWidgetTypes/17/capabilities/settingRefs/compact").isTextual());
    }

    @Test void LegacyDescriptorsWithoutOwnerSchemasStayUnchanged() throws Exception {
        var context = mapper.createObjectNode();
        context.putArray("dashboardWidgetTypes").addObject().put("type", "legacy").putObject("capabilities").putObject("settings").put("known", "BOOLEAN");
        var before = context.deepCopy();
        WidgetCatalogueProjection.compact(context, mapper);
        assertEquals(before, context);
    }

    @Test void repeatedLoadsShareOneCatalogueWithoutMergingTheirDataReferences() throws Exception {
        var request = mapper.createObjectNode();
        var loads = request.putArray("loadResults");
        loads.addObject().put("loaderId", "workflow.context").putObject("data").set("context", context());
        loads.addObject().put("loaderId", "workflow.context").putObject("data").set("context", context());
        ((ObjectNode) request.at("/loadResults/1/data/context/dashboardDataChoices/0")).put("dataRef", "second-owned-source");
        WidgetCatalogueProjection.compactRequest(request, mapper);
        var second = request.at("/loadResults/1/data/context");
        assertFalse(second.has("dashboardWidgetTypes"));
        assertEquals(request.at("/loadResults/0/data/context/dashboardWidgetTypes"),
                request.at(second.path("dashboardWidgetTypesRef").asText()));
        assertEquals("second-owned-source", second.at("/dashboardDataChoices/0/dataRef").asText());
        var once = request.deepCopy();
        WidgetCatalogueProjection.compactRequest(request, mapper);
        assertEquals(once, request);
    }

    @Test void differentOwnerSchemasDoNotShareARepeatedLoadBundle() throws Exception {
        var request = mapper.createObjectNode();
        var loads = request.putArray("loadResults");
        loads.addObject().put("loaderId", "workflow.context").putObject("data").set("context", context());
        loads.addObject().put("loaderId", "workflow.context").putObject("data").set("context", context());
        ((ObjectNode) request.at("/loadResults/1/data/context/dashboardWidgetTypes/0/capabilities/settingsSchema/properties/showTitle")).put("default", true);
        WidgetCatalogueProjection.compactRequest(request, mapper);
        assertTrue(request.at("/loadResults/1/data/context/dashboardWidgetParameters").isObject());
        assertFalse(request.at("/loadResults/1/data/context").has("dashboardWidgetParametersRef"));
    }
}
