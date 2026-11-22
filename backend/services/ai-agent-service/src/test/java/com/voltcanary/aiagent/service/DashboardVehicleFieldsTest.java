package com.voltcanary.aiagent.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DashboardVehicleFieldsTest {

    private final Map<String, Object> context = Map.of(
        "selectedDashboardDeviceRefs",
        List.of("tram"),
        "dashboardDevices",
        List.of(
            Map.of(
                "ref",
                "tram",
                "fields",
                List.of(
                    Map.of("key", "speedKmh", "unit", "km/h"),
                    Map.of("key", "transport.doorsOpen", "unit", "")
                )
            )
        )
    );

    @Test
    void currentSourceValueRemainsScalarWhenModelUsesRedundantMetricParameters() {
        var panel = Map.of(
            "key",
            "current-speed",
            "type",
            "vehicle-value",
            "metric",
            "speedKmh",
            "unit",
            "km/h",
            "reducer",
            "RAW"
        );
        var result = DashboardVehicleFields.normalize(panel, context);
        assertThat(result.get("type")).isEqualTo("vehicle-value");
        assertThat(result.get("vehicleField")).isEqualTo("speedKmh");
        assertThat(result.containsKey("metric")).isFalse();
        assertThat(result.containsKey("unit")).isFalse();
    }

    @Test
    void cannotAdaptUnknownFieldsUnitConversionsOrAggregateReducers() {
        for (var panel : List.of(
            Map.of("type", "vehicle-value", "metric", "unknown"),
            Map.of("type", "vehicle-value", "metric", "speedKmh", "unit", "mph"),
            Map.of("type", "vehicle-value", "metric", "speedKmh", "reducer", "AVG")
        )) {
            assertThat(DashboardVehicleFields.normalize(panel, context)).isEqualTo(panel);
        }
    }
}
