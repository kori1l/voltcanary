package com.voltcanary.analytics.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.analytics.domain.MlModelFamily;
import com.voltcanary.analytics.dto.MlModelVersionDto;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class AnalyticsMlRuntimeDecisionService {

    private final ObjectMapper objectMapper;

    public AnalyticsMlRuntimeDecisionService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public boolean canaryIncluded(UUID accountId, UUID deviceId, UUID modelVersionId, Integer canaryPercent) {
        if (modelVersionId == null) {
            return false;
        }
        int percent = canaryPercent == null ? 0 : Math.max(0, Math.min(100, canaryPercent));
        if (percent >= 100) {
            return true;
        }
        if (percent <= 0 || accountId == null || deviceId == null) {
            return false;
        }
        String key = accountId + "|" + deviceId + "|" + modelVersionId;
        return hashBucket(key) < percent;
    }

    public String compatibilityFallbackReason(MlModelVersionDto model, String deviceType, String metric) {
        List<String> compatibleDeviceTypes = stringList(model.compatibleDeviceTypesJson());
        if (!compatibleDeviceTypes.isEmpty()
                && !blankToEmpty(deviceType).isBlank()
                && compatibleDeviceTypes.stream().noneMatch(type -> type.equalsIgnoreCase(blankToEmpty(deviceType)))) {
            return "INCOMPATIBLE_DEVICE_TYPE";
        }
        List<String> supportedMetrics = stringList(model.supportedMetricsJson());
        if (model.family() != MlModelFamily.FAILURE_PREDICTION
                && !supportedMetrics.isEmpty()
                && !blankToEmpty(metric).isBlank()
                && supportedMetrics.stream().noneMatch(item -> item.equalsIgnoreCase(blankToEmpty(metric)))) {
            return "INCOMPATIBLE_FEATURE_SCHEMA";
        }
        return "";
    }

    public String runtimeModelType(MlModelVersionDto model) {
        return model.family() == MlModelFamily.FAILURE_PREDICTION
                ? "trained_failure_prediction"
                : "trained_tabular_anomaly";
    }

    public List<String> stringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            Object value = objectMapper.readValue(json, Object.class);
            if (!(value instanceof List<?> list)) {
                return List.of();
            }
            return list.stream()
                    .map(Object::toString)
                    .filter(item -> !item.isBlank())
                    .toList();
        } catch (JsonProcessingException exception) {
            return List.of();
        }
    }

    private int hashBucket(String key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            int value = ((digest[0] & 0xff) << 24)
                    | ((digest[1] & 0xff) << 16)
                    | ((digest[2] & 0xff) << 8)
                    | (digest[3] & 0xff);
            return Math.floorMod(value, 100);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private String blankToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
