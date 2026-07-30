package com.voltcanary.analytics.dto;

import com.voltcanary.analytics.domain.MlDatasetSourceType;
import java.time.Instant;
import java.util.UUID;

public record MlLineageDto(
        UUID id,
        UUID datasetVersionId,
        MlDatasetSourceType sourceType,
        String sourceReference,
        String filterJson,
        String selectedDevicesJson,
        String selectedMetricsJson,
        Instant sourceWindowStart,
        Instant sourceWindowEnd,
        String contentHash) {
}
