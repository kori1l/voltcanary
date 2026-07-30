package com.voltcanary.analytics.dto;

import com.voltcanary.analytics.domain.MlDeploymentMode;
import com.voltcanary.analytics.domain.MlDeploymentScope;
import java.time.Instant;
import java.util.UUID;

public record MlDeploymentPolicyDto(
        UUID id,
        UUID accountId,
        MlDeploymentScope scope,
        UUID deviceId,
        String deviceType,
        UUID modelVersionId,
        MlDeploymentMode mode,
        Integer canaryPercent,
        boolean fallbackEnabled,
        UUID updatedBy,
        Instant updatedAt) {
}
