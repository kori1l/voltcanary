package com.voltcanary.analytics.dto;

import com.voltcanary.analytics.domain.MlDeploymentMode;
import com.voltcanary.analytics.domain.MlDeploymentScope;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record MlDeploymentPolicyRequest(
        @NotNull MlDeploymentScope scope,
        UUID deviceId,
        String deviceType,
        UUID modelVersionId,
        @NotNull MlDeploymentMode mode,
        Integer canaryPercent,
        Boolean fallbackEnabled,
        String reason) {
}
