package com.voltcanary.alert.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AlertRuleTestResponse(
        boolean matched,
        int matchCount,
        boolean wouldCreateAlert,
        String severity,
        String previewTitle,
        String previewDescription,
        String evaluationStatus,
        List<AlertRuleTestEvidence> evidence) {

    public record AlertRuleTestEvidence(
            Instant timestamp,
            String metric,
            Double value,
            Double threshold,
            UUID alertId) {
    }
}
