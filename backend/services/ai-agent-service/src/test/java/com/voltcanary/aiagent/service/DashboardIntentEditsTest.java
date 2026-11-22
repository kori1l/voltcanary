package com.voltcanary.aiagent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DashboardIntentEditsTest {

    private final Map<String, Object> map = Map.of(
        "key",
        "map",
        "type",
        "vehicle-map",
        "title",
        "Карта",
        "height",
        8,
        "width",
        "FULL",
        "showTitle",
        false,
        "sourceRef",
        "source-current"
    );
    private final Map<String, Object> speed = Map.of(
        "key",
        "speed",
        "type",
        "time-series",
        "title",
        "Скорость",
        "metric",
        "speedKmh",
        "unit",
        "km/h",
        "height",
        5,
        "width",
        "FULL"
    );
    private final Map<String, Object> door = Map.of(
        "key",
        "door",
        "type",
        "vehicle-value",
        "title",
        "Двери",
        "vehicleField",
        "transport.doorsOpen",
        "height",
        4,
        "width",
        "THIRD"
    );

    private Map<String, Object> previous() {
        return Map.of(
            "name",
            "Мой автобус",
            "period",
            "LAST_24_HOURS",
            "deviceRefs",
            List.of("current"),
            "widgets",
            List.of(map, speed, door)
        );
    }

    @Test
    void changesOnlyNamedWidgetFieldsAndOrderWhilePreservingBindingsAndName() {
        var result = DashboardIntentEdits.apply(
            Map.of(
                "widgetEdits",
                List.of(Map.of("key", "door", "height", 2)),
                "widgetOrder",
                List.of("door", "map", "speed")
            ),
            previous()
        );
        assertThat(result)
            .containsEntry("name", "Мой автобус")
            .containsEntry("deviceRefs", List.of("current"));
        var widgets = (List<?>) result.get("widgets");
        assertThat(widgets.get(1)).isEqualTo(map);
        assertThat(widgets.get(2)).isEqualTo(speed);
        assertThat(((Map<?, ?>) widgets.getFirst()).get("height")).isEqualTo(2);
        assertThat(((Map<?, ?>) widgets.getFirst()).get("vehicleField")).isEqualTo(
            "transport.doorsOpen"
        );
    }

    @Test
    void omissionIsNeverRemovalAndExplicitRemovalDoesNotTouchTheRemainingPanels() {
        var result = DashboardIntentEdits.apply(
            Map.of("widgetEdits", List.of(Map.of("key", "door", "remove", true))),
            previous()
        );
        assertThat(result.get("widgets")).isEqualTo(List.of(map, speed));
        assertThat(
            DashboardIntentEdits.apply(Map.of("name", "Новое имя"), previous()).get("widgets")
        ).isEqualTo(previous().get("widgets"));
    }

    @Test
    void rejectsUnknownKeysDuplicateOrderAndRawNestedDefinitions() {
        for (var edit : List.of(
            Map.of("widgetEdits", List.of(Map.of("key", "foreign", "height", 2))),
            Map.of("widgetOrder", List.of("map", "map", "speed")),
            Map.of("widgetEdits", List.of(Map.of("key", "door", "query", Map.of())))
        )) {
            assertThatThrownBy(() ->
                DashboardIntentEdits.apply(new java.util.HashMap<>(edit), previous())
            ).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void hidesOnlyTheNamedMapsNoticesWithoutChangingItsSourceOrAnyOtherWidget() {
        var result = DashboardIntentEdits.apply(
            Map.of("widgetEdits", List.of(Map.of("key", "map", "showMapNotices", false))),
            previous()
        );
        var panels = (List<?>) result.get("widgets");
        var expected = new java.util.LinkedHashMap<>(map);
        expected.put("showMapNotices", false);
        assertThat(panels).isEqualTo(List.of(expected, speed, door));
    }

    @Test
    void optionalMetricParametersCanBeExplicitlyClearedWhenChangingWidgetType() {
        var edit = new java.util.LinkedHashMap<String, Object>();
        edit.put("key", "speed");
        edit.put("type", "vehicle-timeline");
        edit.put("metric", null);
        edit.put("unit", null);
        edit.put("vehicleField", "transport.doorsOpen");
        edit.put("visualization", "line");
        var result = DashboardIntentEdits.apply(Map.of("widgetEdits", List.of(edit)), previous());
        var changed = (Map<?, ?>) ((List<?>) result.get("widgets")).get(1);
        assertThat(changed.containsKey("metric")).isFalse();
        assertThat(changed.containsKey("unit")).isFalse();
        assertThat(changed.get("vehicleField")).isEqualTo("transport.doorsOpen");
        assertThat(((List<?>) result.get("widgets")).getFirst()).isEqualTo(map);
    }

    @Test
    void partialMoveRetainsTheRelativeOrderAndAllFieldsOfUnaffectedWidgets() {
        var result = DashboardIntentEdits.apply(
            Map.of("moveWidgets", Map.of("keys", List.of("door"), "position", "START")),
            previous()
        );
        assertThat(result.get("widgets")).isEqualTo(List.of(door, map, speed));
        var after = DashboardIntentEdits.apply(
            Map.of(
                "moveWidgets",
                Map.of("keys", List.of("map"), "position", "AFTER", "anchor", "speed")
            ),
            previous()
        );
        assertThat(after.get("widgets")).isEqualTo(List.of(speed, map, door));
    }

    @Test
    void mergesTypedSettingsAndRetainsAnExplicitResetAcrossLaterEdits() {
        var first = DashboardIntentEdits.apply(
            Map.of(
                "widgetEdits",
                List.of(Map.of("key", "map", "settings", Map.of("showMapNotices", false)))
            ),
            previous()
        );
        var reset = new java.util.LinkedHashMap<String, Object>();
        reset.put("showTitle", null);
        var second = DashboardIntentEdits.apply(
            Map.of("widgetEdits", List.of(Map.of("key", "map", "settings", reset))),
            first
        );
        var result = DashboardIntentEdits.apply(
            Map.of(
                "widgetEdits",
                List.of(Map.of("key", "map", "settings", Map.of("showDetails", true)))
            ),
            second
        );
        var changed = (Map<?, ?>) ((List<?>) result.get("widgets")).getFirst();
        assertThat(changed.get("sourceRef")).isEqualTo(map.get("sourceRef"));
        var settings = (Map<?, ?>) changed.get("settings");
        assertThat(settings.get("showMapNotices")).isEqualTo(false);
        assertThat(settings.containsKey("showTitle")).isTrue();
        assertThat(settings.get("showTitle")).isNull();
        assertThat(settings.get("showDetails")).isEqualTo(true);
        assertThat(changed.containsKey("showTitle")).isFalse();
    }

    @Test
    void rejectsRedundantChartsSoRepairMustReuseTheExistingWidget() {
        var duplicate = new java.util.LinkedHashMap<>(speed);
        duplicate.put("key", "another-speed");
        duplicate.put("title", "Another graph");
        assertThatThrownBy(() ->
            DashboardIntentEdits.apply(Map.of("addWidgets", List.of(duplicate)), previous())
        )
            .isInstanceOf(DashboardPreparationRejected.class)
            .hasMessageContaining("duplicates existing key speed");
    }

    @Test
    void addsAndMovesThreeScalarCardsWithoutMovingTheirHistoriesOrMap() {
        var current = Map.of("key", "current", "type", "vehicle-value", "vehicleField", "speedKmh");
        var next = Map.of(
            "key",
            "next",
            "type",
            "vehicle-value",
            "vehicleField",
            "transport.nextStopId"
        );
        var history = Map.of(
            "key",
            "history",
            "type",
            "vehicle-timeline",
            "vehicleField",
            "transport.doorsOpen",
            "visualization",
            "line"
        );
        var baseline = Map.of(
            "name",
            "Tram",
            "widgets",
            List.of(map, current, history, speed, next)
        );
        var result = DashboardIntentEdits.apply(
            Map.of(
                "addWidgets",
                List.of(door),
                "moveWidgets",
                Map.of("keys", List.of("current", "next", "door"), "position", "START")
            ),
            baseline
        );
        assertThat(result.get("widgets")).isEqualTo(
            List.of(current, next, door, map, history, speed)
        );
    }
}
