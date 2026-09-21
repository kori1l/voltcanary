package com.voltcanary.ingestion.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class IngestionMetricCatalogTest {

    private final IngestionMetricCatalog catalog = new IngestionMetricCatalog();

    @Test
    void supportsTenNumericMetricsForCapacityProfile() {
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

        assertThat(metricNames)
                .allSatisfy(metricName -> assertThat(catalog.find(metricName))
                        .as(metricName)
                        .isPresent());
        assertThat(catalog.find("temperatureC").orElseThrow().unit()).isEqualTo("C");
        assertThat(catalog.find("vibrationMmS").orElseThrow().unit()).isEqualTo("mm/s");
        assertThat(catalog.find("runtimeHours").orElseThrow().unit()).isEqualTo("h");
        assertThat(catalog.find("powerFactor").orElseThrow().accepts(1.1)).isFalse();
        assertThat(catalog.find("frequencyHz").orElseThrow().accepts(60.0)).isTrue();
    }
}
