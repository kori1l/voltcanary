package com.voltcanary.analytics.dto;

import com.voltcanary.analytics.domain.MlHorizonLabelGenerationStatus;
import java.time.Instant;
import java.util.UUID;

public record MlHorizonLabelGenerationResponse(
        UUID jobId,
        UUID datasetVersionId,
        MlHorizonLabelGenerationStatus status,
        Instant submittedAt,
        Instant startedAt,
        Instant completedAt,
        Integer progress,
        String message,
        String failureReason,
        int horizonDays,
        String generationVersion,
        boolean includePreventiveMaintenance,
        String inputLineageHash,
        MlHorizonLabelGenerationResult result) {
}
