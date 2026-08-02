package com.voltcanary.analytics.ml;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class FeatureTemplateCatalog {

    public static final String DEFAULT_TEMPLATE_ID = "generic-appliance";
    public static final String DEFAULT_TEMPLATE_VERSION = "template-v1";

    private static final Map<String, FeatureTemplate> TEMPLATES = Map.of(
            key(DEFAULT_TEMPLATE_ID, DEFAULT_TEMPLATE_VERSION),
            new FeatureTemplate(
                    DEFAULT_TEMPLATE_ID,
                    DEFAULT_TEMPLATE_VERSION,
                    List.of("*"),
                    List.of("applianceLoadRatio", "applianceActivityFlag"),
                    List.of("applianceLoadRatio")),
            key("fridge-freezer", DEFAULT_TEMPLATE_VERSION),
            new FeatureTemplate(
                    "fridge-freezer",
                    DEFAULT_TEMPLATE_VERSION,
                    List.of("fridge", "freezer", "refrigerator"),
                    List.of("fridgeTemperatureBandC", "compressorDutyProxy", "defrostCycleRiskFlag"),
                    List.of("compressorDutyProxy")));

    private FeatureTemplateCatalog() {
    }

    public static FeatureTemplate resolve(String templateId, String templateVersion) {
        String id = templateId == null || templateId.isBlank() ? DEFAULT_TEMPLATE_ID : templateId;
        String version = templateVersion == null || templateVersion.isBlank() ? DEFAULT_TEMPLATE_VERSION : templateVersion;
        FeatureTemplate template = TEMPLATES.get(key(id, version));
        if (template == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported ML feature template: " + id + "@" + version);
        }
        return template;
    }

    private static String key(String templateId, String templateVersion) {
        return templateId + "@" + templateVersion;
    }

    public record FeatureTemplate(
            String id,
            String version,
            List<String> compatibleDeviceTypes,
            List<String> generatedColumns,
            List<String> requiredColumns) {
        public Map<String, Object> asMap() {
            return Map.of(
                    "id", id,
                    "version", version,
                    "compatibleDeviceTypes", compatibleDeviceTypes,
                    "generatedColumns", generatedColumns,
                    "requiredColumns", requiredColumns);
        }
    }
}
