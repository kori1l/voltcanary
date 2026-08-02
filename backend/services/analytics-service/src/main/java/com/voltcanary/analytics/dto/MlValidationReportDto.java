package com.voltcanary.analytics.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record MlValidationReportDto(
        UUID id,
        UUID datasetVersionId,
        UUID importedFileId,
        long acceptedRows,
        long rejectedRows,
        long warningCount,
        Map<String, Long> rejectedReasons,
        Map<String, Long> mappingStats,
        Map<String, Object> qualityProfile,
        String summary,
        Instant createdAt) {
}
