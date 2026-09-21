package com.voltcanary.telemetry.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class MetricCatalogTest {

    private final MetricCatalog catalog = new MetricCatalog();

    @Test
    void exposesTenNumericMetricsForCapacityProfile() {
        var metricNames = List.of(
                "activePowerW",
                "voltageV",
                "currentA",
                "energyWh",
                "rssi",
                "temperatureC",
                "vibrationMmS",
                "runtimeHours",
                "powerFactor",
                "frequencyHz");

        assertThat(metricNames).allSatisfy(metricName -> assertThat(catalog.isSupported(metricName)).as(metricName).isTrue());
        assertThat(catalog.all()).hasSize(10);
        assertThat(catalog.find("temperatureC").orElseThrow().unit()).isEqualTo("C");
        assertThat(catalog.find("vibrationMmS").orElseThrow().unit()).isEqualTo("mm/s");
        assertThat(catalog.find("runtimeHours").orElseThrow().unit()).isEqualTo("h");
        assertThat(catalog.find("powerFactor").orElseThrow().accepts(0.95)).isTrue();
        assertThat(catalog.find("frequencyHz").orElseThrow().accepts(60.0)).isTrue();
    }
}
