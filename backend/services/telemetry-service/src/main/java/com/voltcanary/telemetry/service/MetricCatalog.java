package com.voltcanary.telemetry.service;

import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class MetricCatalog {

    private static final Map<String, MetricDefinition> METRICS = Map.ofEntries(
            Map.entry("latitude", new MetricDefinition("deg", -90, 90)),
            Map.entry("longitude", new MetricDefinition("deg", -180, 180)),
            Map.entry("speedKmh", new MetricDefinition("km/h", 0, 300)),
            Map.entry("headingDeg", new MetricDefinition("deg", 0, Math.nextDown(360.0))),
            Map.entry("ignition", new MetricDefinition("state", 0, 1)),
            Map.entry("fuelPercent", new MetricDefinition("%", 0, 100)),
            Map.entry("odometerKm", new MetricDefinition("km", 0, 10000000)),
            Map.entry("activePowerW", new MetricDefinition("W", -100000.0, 100000.0)),
            Map.entry("voltageV", new MetricDefinition("V", 0.0, 1000.0)),
            Map.entry("currentA", new MetricDefinition("A", -1000.0, 1000.0)),
            Map.entry("energyWh", new MetricDefinition("Wh", 0.0, 1_000_000_000.0)),
            Map.entry("rssi", new MetricDefinition("dBm", -150.0, 0.0)),
            Map.entry("temperatureC", new MetricDefinition("C", -50.0, 150.0)),
            Map.entry("vibrationMmS", new MetricDefinition("mm/s", 0.0, 200.0)),
            Map.entry("runtimeHours", new MetricDefinition("h", 0.0, 1_000_000.0)),
            Map.entry("powerFactor", new MetricDefinition("ratio", 0.0, 1.0)),
            Map.entry("frequencyHz", new MetricDefinition("Hz", 0.0, 1000.0)));

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
