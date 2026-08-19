package com.voltcanary.analytics.service;

import com.voltcanary.analytics.domain.MaintenanceMlBehavior;
import com.voltcanary.analytics.domain.MlSettings;
import com.voltcanary.analytics.domain.PredictionMode;
import com.voltcanary.analytics.dto.MlSettingsResponse;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class MlPolicyMapper {

    public MlSettingsResponse response(MlSettings value, String source) {
        return new MlSettingsResponse(
                value.getAccountId(),
                value.getDeviceId(),
                value.isMlEnabled(),
                value.getPredictionMode(),
                value.getConfidenceWarningThreshold(),
                value.getMaintenanceBehavior(),
                value.isExplanationsVisible(),
                source,
                value.getUpdatedAt());
    }

    public MlSettingsResponse defaults(UUID accountId, UUID deviceId, String source) {
        return new MlSettingsResponse(
                accountId,
                deviceId,
                true,
                PredictionMode.MODEL_OR_BASELINE_HEURISTIC,
                .6,
                MaintenanceMlBehavior.REDUCE_CONFIDENCE,
                true,
                source,
                null);
    }

    public MlSettingsResponse inherited(MlSettingsResponse accountPolicy, UUID deviceId) {
        return new MlSettingsResponse(
                accountPolicy.accountId(),
                deviceId,
                accountPolicy.mlEnabled(),
                accountPolicy.predictionMode(),
                accountPolicy.confidenceWarningThreshold(),
                accountPolicy.maintenanceBehavior(),
                accountPolicy.explanationsVisible(),
                "INHERITED",
                accountPolicy.updatedAt());
    }
}
