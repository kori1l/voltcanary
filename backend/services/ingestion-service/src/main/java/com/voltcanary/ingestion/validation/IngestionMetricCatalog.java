package com.voltcanary.ingestion.validation;

import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class IngestionMetricCatalog {

    private final Map<String, MetricDefinition> definitions = Map.ofEntries(
            Map.entry("latitude", new MetricDefinition("deg", -90, 90)),
            Map.entry("longitude", new MetricDefinition("deg", -180, 180)),
            Map.entry("speedKmh", new MetricDefinition("km/h", 0, 300)),
            Map.entry("headingDeg", new MetricDefinition("deg", 0, Math.nextDown(360.0))),
            Map.entry("ignition", new MetricDefinition("state", 0, 1)),
            Map.entry("fuelPercent", new MetricDefinition("%", 0, 100)),
            Map.entry("odometerKm", new MetricDefinition("km", 0, 10000000)),
            Map.entry("activePowerW", new MetricDefinition("W", 0, 100_000)),
            Map.entry("voltageV", new MetricDefinition("V", 0, 1_000)),
            Map.entry("currentA", new MetricDefinition("A", 0, 1_000)),
            Map.entry("energyWh", new MetricDefinition("Wh", 0, 1_000_000_000)),
            Map.entry("rssi", new MetricDefinition("dBm", -150, 0)),
            Map.entry("temperatureC", new MetricDefinition("C", -50, 150)),
            Map.entry("vibrationMmS", new MetricDefinition("mm/s", 0, 200)),
            Map.entry("runtimeHours", new MetricDefinition("h", 0, 1_000_000)),
            Map.entry("powerFactor", new MetricDefinition("ratio", 0, 1)),
            Map.entry("frequencyHz", new MetricDefinition("Hz", 0, 1_000)));

    public Optional<MetricDefinition> find(String metricName) {
        return Optional.ofNullable(definitions.get(metricName));
    }

    public record MetricDefinition(String unit, double min, double max) {

        boolean accepts(double value) {
            return value >= min && value <= max;
        }
    }
}
