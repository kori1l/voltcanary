package com.voltcanary.analytics.dto;

import com.voltcanary.analytics.domain.MaintenanceMlBehavior;
import com.voltcanary.analytics.domain.PredictionMode;
import java.time.Instant;
import java.util.UUID;

public record MlSettingsResponse(UUID accountId, UUID deviceId, boolean mlEnabled,
        PredictionMode predictionMode, double confidenceWarningThreshold,
        MaintenanceMlBehavior maintenanceBehavior, boolean explanationsVisible,
        String source, Instant updatedAt) {}
