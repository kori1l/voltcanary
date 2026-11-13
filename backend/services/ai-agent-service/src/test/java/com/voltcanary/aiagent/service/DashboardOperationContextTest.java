package com.voltcanary.aiagent.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DashboardOperationContextTest {
    @Test void duplicateLegacyMetadataCannotCrowdOutAnAuthorizedMultiDeviceReview() {
        var repeated = IntStream.range(0, 80).mapToObj(i -> Map.of("key", "field" + i,
                "views", List.of("number", "line", "list"), "type", "number", "label", "Field " + i)).toList();
        var devices = List.of(Map.of("ref", "first", "name", "First", "metrics", repeated, "fields", repeated),
                Map.of("ref", "second", "name", "Second", "metrics", repeated, "fields", repeated));
        var choices = List.of(Map.of("dataRef", "current-choice", "deviceRef", "first", "views", List.of("line", "list")));
        var base = Map.of("widgets", IntStream.range(0, 6).mapToObj(i -> Map.of("key", "widget" + i, "deviceRefs", List.of("first"))).toList());
        var context = new LinkedHashMap<String, Object>(Map.of("dashboardDevices", devices,
                "dashboardTemplates", repeated, "dashboardDataChoices", choices, "previousDashboardIntent", base));
        var guard = new PlanningInputGuard();
        assertThatThrownBy(() -> guard.inspect(context)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        DashboardOperationContext.compact(context);
        assertThatCode(() -> guard.inspect(context)).doesNotThrowAnyException();
        assertThat(context).doesNotContainKey("dashboardTemplates");
        assertThat(context.get("dashboardDataChoices")).isSameAs(choices);
        assertThat(context.get("previousDashboardIntent")).isSameAs(base);
        assertThat(context.get("dashboardDevices")).isEqualTo(List.of(Map.of("ref", "first", "name", "First"), Map.of("ref", "second", "name", "Second")));
        context.put("dashboardDataChoices", List.of(Map.of("label", "Bearer sensitive-value")));
        assertThatThrownBy(() -> guard.inspect(context)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
}
