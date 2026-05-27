package com.voltcanary.ingestion.validation;

import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class IngestionMetricCatalog {

    private final Map<String, MetricDefinition> definitions = Map.of(
            "activePowerW", new MetricDefinition("W", 0, 100_000),
            "voltageV", new MetricDefinition("V", 0, 1_000),
            "currentA", new MetricDefinition("A", 0, 1_000),
            "energyWh", new MetricDefinition("Wh", 0, 1_000_000_000),
            "rssi", new MetricDefinition("dBm", -150, 0));

    public Optional<MetricDefinition> find(String metricName) {
        return Optional.ofNullable(definitions.get(metricName));
    }

    public record MetricDefinition(String unit, double min, double max) {

        boolean accepts(double value) {
            return value >= min && value <= max;
        }
    }
}
