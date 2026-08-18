package com.voltcanary.analytics.dto;

public record MlHorizonLabelGenerationResult(
        String lineageHash,
        int generatedLabels,
        int positiveLabels,
        int normalLabels,
        int maintenanceExcludedLabels,
        int unresolvedLabels,
        int unobservableRows) {
}
