package com.voltcanary.analytics.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record MlDatasetValidationResult(
        boolean ready,
        long acceptedRows,
        long rejectedRows,
        long warningCount,
        List<String> messages,
        Map<String, Long> rejectedReasons,
        Map<String, Long> mappingStats,
        Map<UUID, List<UUID>> maintenanceWindowIdsByDevice,
        Map<String, Object> qualityProfile,
        List<MlDatasetTelemetryRow> extractedRows) {
}
