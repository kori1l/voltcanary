package com.voltcanary.analytics.dto;

import com.voltcanary.analytics.domain.MlDeploymentMode;
import com.voltcanary.analytics.domain.MlDeploymentScope;
import java.util.UUID;

public record MlResolvedDeploymentPolicyResponse(
        MlDeploymentScope policySource,
        UUID policyId,
        UUID modelVersionId,
        MlDeploymentMode mode,
        boolean fallbackEnabled,
        String fallbackReason) {
}
