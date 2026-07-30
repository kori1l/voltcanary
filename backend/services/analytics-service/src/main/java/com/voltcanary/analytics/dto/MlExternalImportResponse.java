package com.voltcanary.analytics.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record MlExternalImportResponse(
        UUID datasetId,
        UUID importedFileId,
        UUID validationReportId,
        String contentHash,
        long acceptedRows,
        long rejectedRows,
        long warningCount,
        List<String> validationMessages,
        Instant importedAt) {
}
