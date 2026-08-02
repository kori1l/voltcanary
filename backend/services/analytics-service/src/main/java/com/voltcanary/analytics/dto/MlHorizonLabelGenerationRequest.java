package com.voltcanary.analytics.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record MlHorizonLabelGenerationRequest(
        @NotNull UUID datasetVersionId,
        @Min(1) @Max(365) int horizonDays,
        String generationVersion,
        boolean includePreventiveMaintenance) {
}
