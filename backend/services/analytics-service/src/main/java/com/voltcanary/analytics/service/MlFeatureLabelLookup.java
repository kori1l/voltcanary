package com.voltcanary.analytics.service;

import com.voltcanary.analytics.domain.MlLabel;
import com.voltcanary.analytics.domain.MlLabelType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record MlFeatureLabelLookup(Map<String, MlLabel> labels) {

    public static MlFeatureLabelLookup from(List<MlLabel> labels) {
        Map<String, MlLabel> byKey = new LinkedHashMap<>();
        labels.stream()
                .sorted(Comparator.comparing(MlLabel::getCreatedAt))
                .forEach(label -> keys(label).forEach(key -> byKey.put(key, label)));
        return new MlFeatureLabelLookup(byKey);
    }

    public Map<String, Object> valuesFor(
            UUID deviceId,
            String externalDeviceKey,
            String metricName,
            Instant predictionAt,
            Integer horizonDays) {
        MlLabel label = keys(deviceId, externalDeviceKey, metricName, predictionAt, horizonDays).stream()
                .map(labels::get)
                .filter(item -> item != null)
                .findFirst()
                .orElse(null);
        if (label == null) {
            return Map.of();
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("label", label.getLabel().name());
        values.put("failureWithinHorizon", isPositiveFailureLabel(label.getLabel()));
        values.put("failureOutcomeType", label.getLabel().name());
        values.put("horizonDays", label.getHorizonDays());
        values.put("generationVersion", label.getGenerationVersion() == null ? "" : label.getGenerationVersion());
        values.put("lineageHash", label.getLineageHash() == null ? "" : label.getLineageHash());
        values.put("sourceOutcomeIdsJson", label.getSourceOutcomeIdsJson() == null ? "[]" : label.getSourceOutcomeIdsJson());
        return values;
    }

    private static boolean isPositiveFailureLabel(MlLabelType label) {
        return label == MlLabelType.CONFIRMED_FAILURE || label == MlLabelType.CORRECTIVE_MAINTENANCE;
    }

    private static List<String> keys(MlLabel label) {
        return keys(
                label.getDeviceId(),
                label.getExternalDeviceKey(),
                label.getMetricName(),
                label.getLabelStartAt(),
                label.getHorizonDays());
    }

    private static List<String> keys(
            UUID deviceId,
            String externalDeviceKey,
            String metricName,
            Instant predictionAt,
            Integer horizonDays) {
        String horizon = horizonDays == null ? "" : horizonDays.toString();
        String metric = metricName == null ? "" : metricName;
        List<String> keys = new ArrayList<>();
        if (deviceId != null) {
            keys.add("device|" + deviceId + "|" + metric + "|" + predictionAt + "|" + horizon);
        }
        if (externalDeviceKey != null && !externalDeviceKey.isBlank()) {
            keys.add("external|" + externalDeviceKey + "|" + metric + "|" + predictionAt + "|" + horizon);
        }
        return keys;
    }
}
