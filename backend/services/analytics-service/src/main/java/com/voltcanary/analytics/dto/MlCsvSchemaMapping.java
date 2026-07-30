package com.voltcanary.analytics.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

public record MlCsvSchemaMapping(
        @NotBlank String timestampColumn,
        @NotBlank String deviceKeyColumn,
        @NotBlank String metricNameColumn,
        @NotBlank String metricValueColumn,
        String unitColumn,
        String labelColumn,
        String existingDeviceIdColumn,
        String deviceTypeColumn,
        List<String> metadataColumns,
        String mappingVersion,
        Boolean allowPartialAcceptance) {
}
