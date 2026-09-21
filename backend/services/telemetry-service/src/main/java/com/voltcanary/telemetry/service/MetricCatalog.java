package com.voltcanary.telemetry.service;

import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class MetricCatalog {

    private static final Map<String, MetricDefinition> METRICS = Map.of(
            "activePowerW", new MetricDefinition("W", -100000.0, 100000.0),
            "voltageV", new MetricDefinition("V", 0.0, 1000.0),
            "currentA", new MetricDefinition("A", -1000.0, 1000.0),
            "energyWh", new MetricDefinition("Wh", 0.0, 1_000_000_000.0),
            "rssi", new MetricDefinition("dBm", -150.0, 0.0),
            "temperatureC", new MetricDefinition("C", -50.0, 150.0),
            "vibrationMmS", new MetricDefinition("mm/s", 0.0, 200.0),
            "runtimeHours", new MetricDefinition("h", 0.0, 1_000_000.0),
            "powerFactor", new MetricDefinition("ratio", 0.0, 1.0),
            "frequencyHz", new MetricDefinition("Hz", 0.0, 1000.0));

    public Optional<MetricDefinition> find(String metricName) {
        return Optional.ofNullable(METRICS.get(metricName));
    }

    public Collection<Map.Entry<String, MetricDefinition>> all() {
        return METRICS.entrySet().stream()
                .sorted(Comparator.comparing(Map.Entry::getKey))
                .toList();
    }

    public boolean isSupported(String metricName) {
        return METRICS.containsKey(metricName);
    }

    public record MetricDefinition(String unit, double min, double max) {

        public boolean accepts(double value) {
            return value >= min && value <= max;
        }

        public String type() {
            return "number";
        }
    }
}
