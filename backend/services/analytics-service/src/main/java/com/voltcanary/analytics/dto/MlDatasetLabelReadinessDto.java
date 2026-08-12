package com.voltcanary.analytics.dto;

public record MlDatasetLabelReadinessDto(
        Integer horizonDays,
        long positiveLabels,
        long normalLabels,
        long unresolvedLabels,
        long unobservableRows,
        long maintenanceExcludedLabels,
        double coverageRatio,
        MlWorkbenchStatus status) {
}
