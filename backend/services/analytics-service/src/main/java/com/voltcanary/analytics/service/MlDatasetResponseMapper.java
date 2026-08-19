package com.voltcanary.analytics.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.analytics.domain.MlDataset;
import com.voltcanary.analytics.domain.MlDatasetLineage;
import com.voltcanary.analytics.domain.MlDatasetVersion;
import com.voltcanary.analytics.domain.MlValidationReport;
import com.voltcanary.analytics.dto.MlDatasetSummaryDto;
import com.voltcanary.analytics.dto.MlLineageDto;
import com.voltcanary.analytics.dto.MlValidationReportDto;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class MlDatasetResponseMapper {

    private final ObjectMapper objectMapper;

    public MlDatasetResponseMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public MlDatasetSummaryDto summary(MlDataset dataset, MlDatasetVersion version) {
        return new MlDatasetSummaryDto(
                dataset.getId(),
                version.getId(),
                dataset.getName(),
                dataset.getDescription(),
                version.getSourceType(),
                version.getVersion(),
                version.getStatus(),
                version.getSplitStrategy(),
                version.getFeatureConfigVersion(),
                version.getSourceWindowStart(),
                version.getSourceWindowEnd(),
                version.getCreatedAt(),
                version.getReadyAt(),
                version.getFailureReason(),
                version.getArtifactUri(),
                version.getArtifactHash(),
                version.getImmutableRowReference(),
                version.getDataHash());
    }

    public MlValidationReportDto validationReport(MlValidationReport report) {
        return new MlValidationReportDto(
                report.getId(),
                report.getDatasetVersionId(),
                report.getImportedFileId(),
                report.getAcceptedRows(),
                report.getRejectedRows(),
                report.getWarningCount(),
                readLongMap(report.getRejectedReasonsJson()),
                readLongMap(report.getMappingStatsJson()),
                readObjectMap(report.getQualityProfileJson()),
                report.getSummary(),
                report.getCreatedAt());
    }

    public MlLineageDto lineage(MlDatasetLineage lineage) {
        return new MlLineageDto(
                lineage.getId(),
                lineage.getDatasetVersionId(),
                lineage.getSourceType(),
                lineage.getSourceReference(),
                lineage.getFilterJson(),
                lineage.getSelectedDevicesJson(),
                lineage.getSelectedMetricsJson(),
                lineage.getSourceWindowStart(),
                lineage.getSourceWindowEnd(),
                lineage.getContentHash());
    }

    private Map<String, Long> readLongMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(
                    json,
                    objectMapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Long.class));
        } catch (JsonProcessingException exception) {
            return Map.of();
        }
    }

    private Map<String, Object> readObjectMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(
                    json,
                    objectMapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class));
        } catch (JsonProcessingException exception) {
            return Map.of();
        }
    }
}
