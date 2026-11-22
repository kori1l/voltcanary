package com.voltcanary.aiagent.service;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CurrentWidgetInventoryTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void frozenWrongTargetsFailIndependentEffectsAndInventoryKeepsCurrentAndHistoryDistinct() throws Exception {
        try (
            var stream = getClass().getResourceAsStream("/evaluation/a5-dashboard-failures-v1.json")
        ) {
            var fixture = mapper.readTree(stream);
            assertEquals(1, fixture.at("/historicalScore/passedTurns").asInt());
            assertEquals(5, fixture.at("/historicalScore/turns").asInt());
            assertFalse(fixture.at("/historicalScore/rescored").asBoolean());
            var family = fixture.path("cases").get(1);
            var parameters = mapper.createObjectNode();
            parameters.set("definition", family.path("acceptedBase"));
            var inventory = CurrentWidgetInventory.from(mapper, parameters);
            assertEquals(6, inventory.path("widgets").size());
            for (var item : inventory.path("widgets")) {
                String key = item.path("key").asText();
                if (key.startsWith("current_")) assertEquals("CURRENT", item.path("role").asText());
                if (key.startsWith("history_")) assertEquals("HISTORY", item.path("role").asText());
                assertTrue(item.path("binding").isObject());
                assertTrue(item.at("/breakpoints/desktop/w").asInt() > 0);
            }
            Set<String> targets = Set.of("current_speed", "current_doors", "current_stop");
            for (String breakpoint : List.of("desktop", "tablet", "mobile")) {
                var before = order(family.path("acceptedBase").path("layouts").path(breakpoint));
                var rejected = order(
                    family.path("rejectedCandidate").path("layouts").path(breakpoint)
                );
                assertNotEquals(
                    targets,
                    Set.copyOf(rejected.subList(0, 3)),
                    "frozen top assertion must still fail"
                );
                assertNotEquals(
                    before
                        .stream()
                        .filter(key -> !targets.contains(key))
                        .toList(),
                    rejected
                        .stream()
                        .filter(key -> !targets.contains(key))
                        .toList(),
                    "frozen untouched-order assertion must still fail"
                );
                var corrected = new ArrayList<>(before.stream().filter(targets::contains).toList());
                corrected.addAll(
                    before
                        .stream()
                        .filter(key -> !targets.contains(key))
                        .toList()
                );
                assertEquals(targets, Set.copyOf(corrected.subList(0, 3)));
                assertEquals(
                    before
                        .stream()
                        .filter(key -> !targets.contains(key))
                        .toList(),
                    corrected.subList(3, 6)
                );
                assertEquals(
                    before,
                    mapper.convertValue(inventory.path("order").path(breakpoint), List.class)
                );
            }
        }
    }

    private List<String> order(JsonNode placements) {
        var nodes = new ArrayList<JsonNode>();
        placements.forEach(nodes::add);
        nodes.sort(
            Comparator.comparingInt((JsonNode node) -> node.path("y").asInt()).thenComparingInt(
                node -> node.path("x").asInt()
            )
        );
        return nodes
            .stream()
            .map(node -> node.path("widgetId").asText())
            .toList();
    }
}
