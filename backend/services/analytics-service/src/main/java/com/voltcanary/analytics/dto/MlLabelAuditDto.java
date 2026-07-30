package com.voltcanary.analytics.dto;

import com.voltcanary.analytics.domain.MlLabelType;
import java.time.Instant;
import java.util.UUID;

public record MlLabelAuditDto(
        UUID id,
        UUID labelId,
        MlLabelType previousLabel,
        MlLabelType newLabel,
        UUID editedBy,
        Instant editedAt,
        String reason) {
}
