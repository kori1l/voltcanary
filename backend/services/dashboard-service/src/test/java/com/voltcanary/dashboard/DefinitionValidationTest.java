package com.voltcanary.dashboard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.dashboard.application.DefinitionValidation;
import com.voltcanary.dashboard.domain.DashboardDefinition;
import com.voltcanary.dashboard.domain.DashboardFailure;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DefinitionValidationTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final DefinitionValidation validation = new DefinitionValidation(mapper);
    @Test
    void screensAreAdditiveAndRequireExactlyOneOwnerForEveryWidget() throws Exception {
        DashboardDefinition legacy = definition(mapper.createObjectNode());
        JsonNode tree = mapper.valueToTree(legacy);((com.fasterxml.jackson.databind.node.ObjectNode)tree).remove("screens");
        assertEquals(List.of(), mapper.treeToValue(tree, DashboardDefinition.class).screens());
        var valid = new DashboardDefinition(1, legacy.name(), "", legacy.defaultContext(), legacy.sections(), legacy.widgets(), legacy.layouts(),
                List.of(new DashboardDefinition.Screen("monitoring", "Monitoring", List.of("future")), new DashboardDefinition.Screen("analytics", "Analytics", List.of())));
        assertEquals(valid.screens(), validation.validate(valid).screens());
        for (var screens : List.of(List.of(new DashboardDefinition.Screen("a", "A", List.of("future")),new DashboardDefinition.Screen("b", "B", List.of("future"))),
                List.of(new DashboardDefinition.Screen("a", "A", List.of())),List.of(new DashboardDefinition.Screen("a", " ", List.of("future"))),
                Collections.nCopies(9,new DashboardDefinition.Screen("a", "A", List.of("future"))))) {
            assertThrows(DashboardFailure.class, () -> validation.validate(new DashboardDefinition(1, legacy.name(), "", legacy.defaultContext(), legacy.sections(), legacy.widgets(), legacy.layouts(), screens)));
        }
    }

    @Test
    void preservesUnsupportedWidgetPayloadWithoutExecutingIt() throws Exception {
        JsonNode payload = mapper.readTree("{\"futureOptions\":{\"mode\":\"preserve\",\"value\":0}}");
        DashboardDefinition definition = definition(payload);
        assertEquals(payload, validation.validate(definition).widgets().getFirst().payload());
        assertEquals(99, definition.widgets().getFirst().typeVersion());
    }

    @Test
    void rejectsSecretsEvenInsideUnsupportedNestedPayload() throws Exception {
        for (String field : List.of("apiKey", "sourceToken", "password", "Authorization", "encryptedSecret")) {
            DashboardFailure failure = assertThrows(DashboardFailure.class,
                    () -> validation.validate(definition(mapper.readTree("{\"futureOptions\":{\"" + field + "\":\"hidden\"}}"))));
            assertEquals("VALIDATION_ERROR", failure.code());
        }
    }

    @Test
    void forbidsExecutableContentAndFreeHttpDefinitions() throws Exception {
        for (String payload : List.of("{\"url\":\"https://example.invalid/read\"}",
                "{\"note\":\"<script>alert(1)</script>\"}", "{\"sql\":\"SELECT 1\"}",
                "{\"href\":\"//example.invalid\"}", "{\"note\":\"Bearer private\"}")) {
            assertThrows(DashboardFailure.class, () -> validation.validate(definition(mapper.readTree(payload))));
        }
        validation.validate(definition(mapper.readTree("{\"href\":\"/devices/00000000-0000-0000-0000-000000000001\"}")));
    }

    @Test
    void enforcesWidgetQuotaNamesResourceIdsAndLayoutBounds() throws Exception {
        DashboardDefinition valid = definition(mapper.createObjectNode());
        assertThrows(DashboardFailure.class, () -> validation.validate(new DashboardDefinition(1, " ", "", null, List.of(), List.of(), mapper.createObjectNode())));
        assertThrows(DashboardFailure.class, () -> validation.validate(new DashboardDefinition(1, "Valid", "", null, List.of(), Collections.nCopies(25, valid.widgets().getFirst()), mapper.createObjectNode())));
        assertThrows(DashboardFailure.class, () -> validation.validate(definition(mapper.readTree("{\"deviceIds\":[\"foreign-not-uuid\"]}"))));
        JsonNode layout = mapper.readTree("{\"mobile\":[{\"widgetId\":\"future\",\"x\":0,\"y\":0,\"w\":2,\"h\":1}]}");
        assertThrows(DashboardFailure.class, () -> validation.validate(new DashboardDefinition(1, "Valid", "", null, List.of(), valid.widgets(), layout)));
    }

    @Test
    void integerOverflowCannotWrapLayoutCoordinatesIntoValidBounds() throws Exception {
        DashboardDefinition valid = definition(mapper.createObjectNode());
        JsonNode layout = mapper.readTree("{\"desktop\":[{\"widgetId\":\"future\",\"x\":4294967296,\"y\":0,\"w\":1,\"h\":1}]}");
        assertThrows(DashboardFailure.class, () -> validation.validate(new DashboardDefinition(1, "Valid", "",
                mapper.createObjectNode(), List.of(), valid.widgets(), layout)));
    }

    @Test
    void validatesCustomScreenGridAndPlacementBounds() throws Exception {
        var legacy = definition(mapper.createObjectNode());
        JsonNode grid = mapper.readTree("{\"desktop\":{\"columns\":24,\"rowHeight\":32,\"gap\":6}}");
        JsonNode layout = mapper.readTree("{\"desktop\":[{\"widgetId\":\"future\",\"x\":18,\"y\":0,\"w\":6,\"h\":3}]}");
        var custom = new DashboardDefinition(1, legacy.name(), "", legacy.defaultContext(), legacy.sections(),
                legacy.widgets(), layout, List.of(new DashboardDefinition.Screen("main", "Main", List.of("future"), grid)));
        validation.validate(custom);
        var restored = mapper.readValue(mapper.writeValueAsString(custom), DashboardDefinition.class);
        assertEquals(grid, restored.screens().getFirst().grid());
        for (String invalid : List.of(
                "{\"desktop\":{\"columns\":49,\"rowHeight\":32,\"gap\":6}}",
                "{\"desktop\":{\"columns\":2.5,\"rowHeight\":32,\"gap\":6}}",
                "{\"desktop\":{\"columns\":24,\"rowHeight\":0,\"gap\":6}}",
                "{\"desktop\":{\"columns\":24,\"rowHeight\":32,\"gap\":33}}",
                "{\"desktop\":{\"columns\":12,\"rowHeight\":32,\"gap\":6}}")) {
            JsonNode invalidGrid = mapper.readTree(invalid);
            assertThrows(DashboardFailure.class, () -> validation.validate(new DashboardDefinition(1, legacy.name(), "",
                    legacy.defaultContext(), legacy.sections(), legacy.widgets(), layout,
                    List.of(new DashboardDefinition.Screen("main", "Main", List.of("future"), invalidGrid)))));
        }
    }

    private DashboardDefinition definition(JsonNode payload) {
        DashboardDefinition.Widget widget = new DashboardDefinition.Widget("future", "future-widget", 99,
                "Unknown preserved widget", null, null, null, null, payload);
        return new DashboardDefinition(1, "Personal overview", "", mapper.createObjectNode(),
                List.of(), List.of(widget), mapper.createObjectNode());
    }
}
