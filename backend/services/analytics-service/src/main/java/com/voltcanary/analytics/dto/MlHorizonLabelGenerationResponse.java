package com.voltcanary.analytics.dto;

import java.util.UUID;

public record MlHorizonLabelGenerationResponse(
        UUID datasetVersionId,
        int horizonDays,
        String generationVersion,
        String lineageHash,
        int generatedLabels,
        int positiveLabels,
        int normalLabels,
        int maintenanceExcludedLabels,
        int unresolvedLabels,
        int unobservableRows) {
}
