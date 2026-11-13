package com.voltcanary.aiagent.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DashboardDataReferencesTest {
    private final AuthorizedPlanningContext.Prepared context = new AuthorizedPlanningContext.Prepared(
            new AuthorizedPlanningContext.Scope(UUID.randomUUID(), UUID.randomUUID()), Map.of(), List.of(
            new AuthorizedPlanningContext.ResourceRef("device-ref", "device", "device", Map.of("name", "Owned")),
            new AuthorizedPlanningContext.ResourceRef("source-ref", "source", "source", Map.of("deviceId", "device")),
            new AuthorizedPlanningContext.ResourceRef("data-ref", "dashboard-data", "identity", Map.of(
                    "deviceId", "device", "sourceId", "source", "family", "RECORDED_VEHICLE_FIELD", "key", "recorded.state", "unit", "",
                    "views", List.of(Map.of("widgetType", "vehicle-timeline", "visualizations", List.of("line", "list")))))));

    @Test void exactReferenceSuppliesDataAndPreservesPresentation() {
        var result = DashboardDataReferences.resolve(Map.of("dataRef", "data-ref", "type", "vehicle-timeline",
                "visualization", "line", "title", "Doors", "height", 2, "settings", Map.of("showTitle", false)), context);
        assertThat(result.get("vehicleField")).isEqualTo("recorded.state");
        assertThat(result.get("sourceRef")).isEqualTo("source-ref");
        assertThat(result.get("deviceRefs")).isEqualTo(List.of("device-ref"));
        assertThat(result.get("settings")).isEqualTo(Map.of("showTitle", false));
        assertThat(result.get("height")).isEqualTo(2);
        assertThat(result.containsKey("metric")).isFalse();
        assertThat(result.containsKey("dataRef")).isFalse();
    }

    @Test void cannotOverrideDataIdentityOrInventACompatibleWidget() {
        for (Map<String, Object> wrong : List.<Map<String, Object>>of(
                Map.of("dataRef", "foreign-ref", "type", "vehicle-timeline"),
                Map.of("dataRef", "data-ref", "type", "time-series"),
                Map.of("dataRef", "data-ref", "type", "vehicle-timeline", "metric", "recorded.state"),
                Map.of("dataRef", "data-ref", "type", "vehicle-timeline", "deviceRefs", List.of("other")),
                Map.of("dataRef", "data-ref", "type", "vehicle-timeline", "vehicleField", "other"),
                Map.of("dataRef", "data-ref", "type", "vehicle-timeline", "sourceRef", "other"))) {
            assertThatThrownBy(() -> DashboardDataReferences.resolve(wrong, context)).isInstanceOf(DashboardPreparationRejected.class);
        }
    }

    @Test void registeredMetricKeepsItsActualUnitAndSourceAmbiguityCannotBeBypassed() {
        var metric = new AuthorizedPlanningContext.ResourceRef("metric-choice", "dashboard-data", "metric", Map.of(
                "deviceId", "device", "deviceRef", "device-ref", "family", "REGISTERED_NUMERIC_METRIC", "key", "power", "unit", "W",
                "views", List.of(Map.of("widgetType", "time-series", "visualizations", List.of("line")))));
        var refs = new java.util.ArrayList<>(context.refs());
        refs.add(metric);
        var prepared = new AuthorizedPlanningContext.Prepared(context.scope(), context.context(), refs);
        var result = DashboardDataReferences.resolve(Map.of("dataRef", "metric-choice", "type", "time-series", "visualization", "line"), prepared);
        assertThat(result.get("metric")).isEqualTo("power");
        assertThat(result.get("unit")).isEqualTo("W");
        assertThatThrownBy(() -> DashboardDataReferences.resolve(Map.of("dataRef", "metric-choice", "type", "time-series", "unit", "kW"), prepared))
                .isInstanceOf(DashboardPreparationRejected.class);
        var ambiguous = new java.util.LinkedHashMap<>(context.refs().getLast().summary());
        ambiguous.put("requiresSourceChoice", true);
        ambiguous.put("externalSourceId", "source-A");
        ambiguous.put("deviceRef", "device-ref");
        refs.add(new AuthorizedPlanningContext.ResourceRef("ambiguous", "dashboard-data", "ambiguous", ambiguous));
        var second = new java.util.LinkedHashMap<>(ambiguous);
        second.put("sourceId", "second");
        second.put("externalSourceId", "source-B");
        refs.add(new AuthorizedPlanningContext.ResourceRef("second", "dashboard-data", "second", second));
        var unresolved = new AuthorizedPlanningContext.Prepared(context.scope(), context.context(), refs);
        assertThatThrownBy(() -> DashboardDataReferences.resolve(Map.of("dataRef", "ambiguous", "type", "vehicle-timeline"), unresolved))
                .isInstanceOfSatisfying(DashboardSourceChoiceRequired.class, required -> {
                    var plan = new com.voltcanary.aiagent.model.AgentPlan(UUID.randomUUID(), UUID.randomUUID(), "Create", "Create", "Create", List.of(), List.of(), java.time.Instant.now());
                    var question = required.question(plan);
                    assertThat(question.actions()).isEmpty();
                    assertThat(question.explanation()).contains("source-A", "source-B");
                    assertThat(question.clarification().requiresExplicitChoice()).isTrue();
                });
        assertThatThrownBy(() -> DashboardDataReferences.resolve(Map.of("type", "vehicle-timeline", "vehicleField", "recorded.state",
                "deviceRefs", List.of("device-ref"), "sourceRef", "source-ref"), unresolved))
                .isInstanceOf(DashboardSourceChoiceRequired.class);
    }
}
