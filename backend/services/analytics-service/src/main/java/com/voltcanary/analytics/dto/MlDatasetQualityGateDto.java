package com.voltcanary.analytics.dto;

public record MlDatasetQualityGateDto(
        String key,
        String label,
        MlWorkbenchGateStatus status,
        Object actual,
        Object threshold,
        boolean blocking,
        String message) {
}
