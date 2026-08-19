package com.voltcanary.analytics.service;

import com.voltcanary.analytics.domain.PredictionMode;
import com.voltcanary.analytics.dto.MlSettingsRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class MlPolicyValidator {

    public void validate(MlSettingsRequest value) {
        if (value.confidenceWarningThreshold() < 0 || value.confidenceWarningThreshold() > 1) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Confidence warning threshold must be between 0 and 1");
        }
        if (!value.mlEnabled() && value.predictionMode() != PredictionMode.RULE_BASED_ONLY) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Disabled ML requires RULE_BASED_ONLY mode");
        }
    }
}
