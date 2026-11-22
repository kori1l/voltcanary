package com.voltcanary.aiagent.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DashboardModelContextTest {

    @Test
    void removesCompetingLegacyCatalogueAndRetainsExplicitIndependentWidgetPeriods() {
        var widgets = List.of(
            Map.of("key", "speed", "period", "LAST_24_HOURS"),
            Map.of("key", "map", "period", "LAST_1_HOUR")
        );
        var previous = Map.of(
            "name",
            "Bus",
            "period",
            "LAST_24_HOURS",
            "widgets",
            widgets,
            "templateRef",
            "obsolete"
        );
        var original = Map.<String, Object>of(
            "dashboardTemplates",
            List.of("legacy"),
            "dashboardWidgetTypes",
            List.of("vehicle-map", "time-series"),
            "previousDashboardIntent",
            previous
        );
        var reduced = DashboardModelContext.forModel(original);
        assertThat(reduced).doesNotContainKey("dashboardTemplates");
        assertThat(original).containsKey("dashboardTemplates");
        var hint = (Map<?, ?>) reduced.get("previousDashboardIntent");
        assertThat(hint.containsKey("templateRef")).isFalse();
        var panels = (List<?>) hint.get("widgets");
        assertThat(((Map<?, ?>) panels.get(0)).containsKey("period")).isFalse();
        assertThat(((Map<?, ?>) panels.get(1)).get("period")).isEqualTo("LAST_1_HOUR");
    }
}
