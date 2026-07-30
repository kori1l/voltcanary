package com.voltcanary.analytics.dto;

import com.voltcanary.analytics.domain.MaintenanceMlBehavior;
import com.voltcanary.analytics.domain.PredictionMode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record MlSettingsRequest(
        boolean mlEnabled,
        @NotNull PredictionMode predictionMode,
        @Min(0) @Max(1) double confidenceWarningThreshold,
        @NotNull MaintenanceMlBehavior maintenanceBehavior,
        boolean explanationsVisible) {}
