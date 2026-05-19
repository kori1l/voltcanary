package com.voltcanary.simulator.yaml;

import com.fasterxml.jackson.databind.JsonNode;
import com.voltcanary.simulator.domain.SimulationProfile;
import com.voltcanary.simulator.domain.SimulationProtocol;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Set;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class SimulationYamlValidator {

    private final SimulationYamlParser parser;
    private static final Set<String> METRIC_TYPES = Set.of("constant", "cycle", "derived", "accumulator");
    private static final Set<String> ANOMALY_TYPES = Set.of("POWER_SPIKE", "CONSUMPTION_GROWTH", "OFFLINE");

    public SimulationYamlValidator(SimulationYamlParser parser) {
        this.parser = parser;
    }

    public List<ValidationError> validate(String yaml) {
        List<ValidationError> errors = new ArrayList<>();
        JsonNode root;
        try {
            root = parser.tree(yaml);
        } catch (IllegalArgumentException exception) {
            return List.of(new ValidationError("$", exception.getMessage()));
        }
        if (!root.has("schemaVersion")) {
            errors.add(new ValidationError("schemaVersion", "schemaVersion is required"));
        } else if (root.path("schemaVersion").asInt() != 1) {
            errors.add(new ValidationError("schemaVersion", "only schemaVersion 1 is supported"));
        }
        validateEnum(errors, "simulator.profile", root.path("simulator").path("profile").asText(null), SimulationProfile.class);
        validateEnum(errors, "simulator.protocol", root.path("simulator").path("protocol").asText(null), SimulationProtocol.class);
        if (root.path("target").path("sourceId").asText("").isBlank()) {
            errors.add(new ValidationError("target.sourceId", "sourceId is required"));
        }
        try {
            Duration interval = parser.parseDuration(root.path("schedule").path("interval").asText("5s"));
            if (interval.isZero() || interval.isNegative()) {
                errors.add(new ValidationError("schedule.interval", "interval must be positive"));
            }
        } catch (RuntimeException exception) {
            errors.add(new ValidationError("schedule.interval", "interval must be a duration like 5s or 1m"));
        }
        JsonNode metrics = root.path("metrics");
        if (!metrics.isObject() || metrics.isEmpty()) {
            errors.add(new ValidationError("metrics", "at least one metric is required"));
        } else {
            validateMetrics(errors, metrics);
        }
        validateDurationIfPresent(errors, "schedule.duration", root.path("schedule").path("duration"), false);
        validateAnomalies(errors, root.path("anomalies"));
        return errors;
    }

    private void validateMetrics(List<ValidationError> errors, JsonNode metrics) {
        metrics.fields().forEachRemaining(entry -> {
            String path = "metrics." + entry.getKey();
            JsonNode metric = entry.getValue();
            String type = metric.path("type").asText("");
            if (!METRIC_TYPES.contains(type)) {
                errors.add(new ValidationError(path + ".type", "unsupported metric type"));
            }
            validateNonNegative(errors, path + ".value", metric.path("value"));
            if ("cycle".equals(type)) {
                JsonNode onDuration = metric.path("cycle").path("onDuration");
                JsonNode offDuration = metric.path("cycle").path("offDuration");
                validateDurationRange(errors, path + ".cycle.onDuration", onDuration);
                validateDurationRange(errors, path + ".cycle.offDuration", offDuration);
                validateNonNegative(errors, path + ".onValue.mean", metric.path("onValue").path("mean"));
                validateNonNegative(errors, path + ".offValue.mean", metric.path("offValue").path("mean"));
            }
            if ("derived".equals(type) && metric.path("expression").asText("").isBlank()) {
                errors.add(new ValidationError(path + ".expression", "expression is required"));
            }
            if ("accumulator".equals(type) && metric.path("from").asText("").isBlank()) {
                errors.add(new ValidationError(path + ".from", "source metric is required"));
            }
        });
    }

    private void validateAnomalies(List<ValidationError> errors, JsonNode anomalies) {
        if (anomalies.isMissingNode() || anomalies.isNull()) {
            return;
        }
        if (!anomalies.isArray()) {
            errors.add(new ValidationError("anomalies", "anomalies must be a list"));
            return;
        }
        for (int index = 0; index < anomalies.size(); index++) {
            JsonNode anomaly = anomalies.get(index);
            String path = "anomalies[" + index + "]";
            String type = anomaly.path("type").asText("");
            if (!ANOMALY_TYPES.contains(type)) {
                errors.add(new ValidationError(path + ".type", "unsupported anomaly type"));
            }
            validateDurationIfPresent(errors, path + ".startAfter", anomaly.path("startAfter"), false);
            validateDurationIfPresent(errors, path + ".duration", anomaly.path("duration"), false);
            validateNonNegative(errors, path + ".powerW", anomaly.path("powerW"));
            validateNonNegative(errors, path + ".growthPercent", anomaly.path("growthPercent"));
            validateDurationIfPresent(errors, path + ".rampDuration", anomaly.path("rampDuration"), false);
        }
    }

    private void validateDurationRange(List<ValidationError> errors, String path, JsonNode node) {
        try {
            Duration min = parser.parseDuration(node.path("min").asText("0s"));
            Duration max = parser.parseDuration(node.path("max").asText("0s"));
            if (min.isNegative() || max.isNegative() || min.compareTo(max) >= 0) {
                errors.add(new ValidationError(path, "min must be less than max"));
            }
        } catch (RuntimeException exception) {
            errors.add(new ValidationError(path, "duration range must use values like 5s or 1m"));
        }
    }

    private void validateDurationIfPresent(List<ValidationError> errors, String path, JsonNode node, boolean allowZero) {
        if (node == null || node.isMissingNode() || node.isNull() || node.asText("").isBlank()) {
            return;
        }
        try {
            Duration duration = parser.parseDuration(node.asText());
            if (duration.isNegative() || (!allowZero && duration.isZero())) {
                errors.add(new ValidationError(path, "duration must be positive"));
            }
        } catch (RuntimeException exception) {
            errors.add(new ValidationError(path, "duration must use values like 5s or 1m"));
        }
    }

    private void validateNonNegative(List<ValidationError> errors, String path, JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        if ("metrics.rssi.value".equals(path)) {
            if (!node.isNumber()) {
                errors.add(new ValidationError(path, "value must be numeric"));
            }
            return;
        }
        if (!node.isNumber() || node.asDouble() < 0) {
            errors.add(new ValidationError(path, "value must be non-negative"));
        }
    }

    private <E extends Enum<E>> void validateEnum(List<ValidationError> errors, String path, String value, Class<E> enumType) {
        if (value == null || value.isBlank()) {
            errors.add(new ValidationError(path, "value is required"));
            return;
        }
        try {
            Enum.valueOf(enumType, value);
        } catch (IllegalArgumentException exception) {
            errors.add(new ValidationError(path, "unsupported value"));
        }
    }
}
