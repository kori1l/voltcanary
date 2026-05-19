package com.voltcanary.simulator.yaml;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class SimulationYamlParser {

    private final YAMLMapper mapper = new YAMLMapper();

    public JsonNode tree(String yaml) {
        try {
            return mapper.readTree(yaml);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid YAML syntax: " + exception.getMessage(), exception);
        }
    }

    public SimulationConfigModel parse(String yaml) {
        JsonNode root = tree(yaml);
        JsonNode simulator = root.path("simulator");
        JsonNode schedule = root.path("schedule");
        return new SimulationConfigModel(
                root.path("schemaVersion").asInt(),
                simulator.path("name").asText("simulator"),
                simulator.path("profile").asText("GENERIC_APPLIANCE"),
                simulator.path("mode").asText("NORMAL"),
                simulator.path("protocol").asText("HTTP"),
                new TargetConfig(root.path("target").path("sourceId").asText()),
                new ScheduleConfig(
                        parseDuration(schedule.path("interval").asText("5s")),
                        parseDuration(schedule.path("startDelay").asText("0s")),
                        parseDuration(schedule.path("duration").asText("0s")),
                        schedule.path("speed").asDouble(1.0)),
                parseMetrics(root.path("metrics")),
                parseAnomalies(root.path("anomalies")));
    }

    Map<String, MetricConfig> parseMetrics(JsonNode metrics) {
        Map<String, MetricConfig> parsed = new LinkedHashMap<>();
        if (!metrics.isObject()) {
            return parsed;
        }
        metrics.fields().forEachRemaining(entry -> {
            JsonNode metric = entry.getValue();
            JsonNode cycle = metric.path("cycle");
            parsed.put(entry.getKey(), new MetricConfig(
                    entry.getKey(),
                    metric.path("type").asText(),
                    nullableDouble(metric.path("value")),
                    nullableDouble(metric.path("onValue").path("mean")),
                    nullableDouble(metric.path("onValue").path("noise")),
                    nullableDuration(cycle.path("onDuration").path("min")),
                    nullableDuration(cycle.path("onDuration").path("max")),
                    nullableDuration(cycle.path("offDuration").path("min")),
                    nullableDuration(cycle.path("offDuration").path("max")),
                    metric.path("expression").asText(null),
                    metric.path("from").asText(null)));
        });
        return parsed;
    }

    List<AnomalyConfig> parseAnomalies(JsonNode anomalies) {
        List<AnomalyConfig> parsed = new ArrayList<>();
        if (!anomalies.isArray()) {
            return parsed;
        }
        for (JsonNode anomaly : anomalies) {
            parsed.add(new AnomalyConfig(
                    anomaly.path("type").asText(),
                    anomaly.path("enabled").asBoolean(false),
                    parseDuration(anomaly.path("startAfter").asText("0s")),
                    parseDuration(anomaly.path("duration").asText("0s")),
                    nullableDouble(anomaly.path("powerW")),
                    nullableDouble(anomaly.path("growthPercent")),
                    nullableDuration(anomaly.path("rampDuration"))));
        }
        return parsed;
    }

    public Duration parseDuration(String value) {
        if (value == null || value.isBlank()) {
            return Duration.ZERO;
        }
        String trimmed = value.trim().toLowerCase();
        if (trimmed.startsWith("pt")) {
            return Duration.parse(value);
        }
        long amount = Long.parseLong(trimmed.substring(0, trimmed.length() - 1));
        if (trimmed.endsWith("s")) {
            return Duration.ofSeconds(amount);
        }
        if (trimmed.endsWith("m")) {
            return Duration.ofMinutes(amount);
        }
        if (trimmed.endsWith("h")) {
            return Duration.ofHours(amount);
        }
        return Duration.parse(value);
    }

    private Double nullableDouble(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? null : node.asDouble();
    }

    private Duration nullableDuration(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() || node.asText("").isBlank() ? null : parseDuration(node.asText());
    }
}
