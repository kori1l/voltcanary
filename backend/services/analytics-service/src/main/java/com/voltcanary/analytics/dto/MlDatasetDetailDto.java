package com.voltcanary.analytics.dto;

import java.util.List;

public record MlDatasetDetailDto(
        MlDatasetSummaryDto dataset,
        List<MlValidationReportDto> validationReports,
        List<MlLineageDto> lineage) {
}
