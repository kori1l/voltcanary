package com.voltcanary.analytics.dto;

import com.voltcanary.analytics.domain.MlDeploymentMode;
import com.voltcanary.analytics.domain.MlDeploymentScope;
import java.util.UUID;

public record MlResolvedDeploymentPolicyResponse(
        MlDeploymentScope policySource,
        UUID policyId,
        UUID modelVersionId,
        MlDeploymentMode mode,
        String resolvedDeviceType,
        Integer canaryPercent,
        Boolean canaryIncluded,
        boolean fallbackEnabled,
        String fallbackReason) {

    public MlResolvedDeploymentPolicyResponse(
            MlDeploymentScope policySource,
            UUID policyId,
            UUID modelVersionId,
            MlDeploymentMode mode,
            boolean fallbackEnabled,
            String fallbackReason) {
        this(policySource, policyId, modelVersionId, mode, null, null, null, fallbackEnabled, fallbackReason);
    }
}
