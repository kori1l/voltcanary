package com.voltcanary.analytics.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.analytics.dto.MlDatasetDetailDto;
import com.voltcanary.analytics.dto.MlDatasetSummaryDto;
import com.voltcanary.analytics.dto.MlDatasetWorkbenchPreviewRequest;
import com.voltcanary.analytics.dto.MlDatasetWorkbenchPreviewResponse;
import com.voltcanary.analytics.dto.MlInternalDatasetRequest;
import com.voltcanary.analytics.dto.MlInternalDatasetResponse;
import com.voltcanary.analytics.dto.MlLifecycleActionRequest;
import com.voltcanary.analytics.dto.MlLifecycleImpactResponse;
import com.voltcanary.analytics.repository.MaintenanceOutcomeReadModelRepository;
import com.voltcanary.analytics.repository.MaintenanceWindowReadModelRepository;
import com.voltcanary.analytics.repository.MlCanonicalMetricRowRepository;
import com.voltcanary.analytics.repository.MlDatasetLineageRepository;
import com.voltcanary.analytics.repository.MlDatasetRepository;
import com.voltcanary.analytics.repository.MlDatasetVersionRepository;
import com.voltcanary.analytics.repository.MlFeatureSnapshotRepository;
import com.voltcanary.analytics.repository.MlLabelRepository;
import com.voltcanary.analytics.repository.MlModelVersionRepository;
import com.voltcanary.analytics.repository.MlTrainingRunRepository;
import com.voltcanary.analytics.repository.MlValidationReportRepository;
import com.voltcanary.analytics.telemetry.TelemetryClient;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class MlDatasetService {

    private final MlDatasetPreviewService previewService;
    private final MlDatasetMaterializationService materializationService;
    private final MlDatasetQueryService queryService;
    private final MlDatasetLifecycleService lifecycleService;

    @Autowired
    public MlDatasetService(
            MlDatasetPreviewService previewService,
            MlDatasetMaterializationService materializationService,
            MlDatasetQueryService queryService,
            MlDatasetLifecycleService lifecycleService) {
        this.previewService = previewService;
        this.materializationService = materializationService;
        this.queryService = queryService;
        this.lifecycleService = lifecycleService;
    }

    public MlDatasetService(
            MlDatasetRepository datasetRepository,
            MlDatasetVersionRepository datasetVersionRepository,
            MlValidationReportRepository validationReportRepository,
            MlDatasetLineageRepository lineageRepository,
            MlLabelRepository labelRepository,
            MlFeatureSnapshotRepository featureSnapshotRepository,
            MlTrainingRunRepository trainingRunRepository,
            MlModelVersionRepository modelVersionRepository,
            MlCanonicalMetricRowRepository canonicalMetricRowRepository,
            MaintenanceOutcomeReadModelRepository maintenanceOutcomeRepository,
            MaintenanceWindowReadModelRepository maintenanceWindowRepository,
            TelemetryClient telemetryClient,
            ObjectMapper objectMapper) {
        MlDatasetSupportService support = new MlDatasetSupportService(objectMapper);
        MlDatasetValidationService validationService = new MlDatasetValidationService(
                maintenanceWindowRepository,
                telemetryClient,
                support);
        MlDatasetLabelReadinessService labelReadinessService = new MlDatasetLabelReadinessService(
                maintenanceOutcomeRepository,
                maintenanceWindowRepository,
                support);
        MlDatasetPreviewService localPreviewService = new MlDatasetPreviewService(
                maintenanceOutcomeRepository,
                maintenanceWindowRepository,
                telemetryClient,
                validationService,
                labelReadinessService,
                support);
        MlDatasetResponseMapper mapper = new MlDatasetResponseMapper(objectMapper);
        this.previewService = localPreviewService;
        this.materializationService = new MlDatasetMaterializationService(
                datasetRepository,
                datasetVersionRepository,
                validationReportRepository,
                lineageRepository,
                canonicalMetricRowRepository,
                validationService,
                labelReadinessService,
                localPreviewService,
                support);
        this.queryService = new MlDatasetQueryService(
                datasetRepository,
                datasetVersionRepository,
                validationReportRepository,
                lineageRepository,
                mapper);
        this.lifecycleService = new MlDatasetLifecycleService(
                datasetRepository,
                datasetVersionRepository,
                labelRepository,
                featureSnapshotRepository,
                trainingRunRepository,
                modelVersionRepository,
                mapper);
    }

    public MlDatasetWorkbenchPreviewResponse previewInternalTelemetryDataset(
            UUID accountId,
            MlDatasetWorkbenchPreviewRequest request) {
        return previewService.previewInternalTelemetryDataset(accountId, request);
    }

    public MlInternalDatasetResponse createFromInternalTelemetry(
            UUID accountId,
            UUID userId,
            MlInternalDatasetRequest request) {
        return materializationService.createFromInternalTelemetry(accountId, userId, request);
    }

    public List<MlDatasetSummaryDto> list(UUID accountId) {
        return queryService.list(accountId);
    }

    public List<MlDatasetSummaryDto> list(UUID accountId, String statusFilter) {
        return queryService.list(accountId, statusFilter);
    }

    public MlDatasetDetailDto detail(UUID accountId, UUID datasetVersionId) {
        return queryService.detail(accountId, datasetVersionId);
    }

    public MlLifecycleImpactResponse impact(UUID accountId, UUID datasetVersionId, String action) {
        return lifecycleService.impact(accountId, datasetVersionId, action);
    }

    public MlDatasetSummaryDto archive(UUID accountId, UUID actorId, UUID datasetVersionId, MlLifecycleActionRequest request) {
        return lifecycleService.archive(accountId, actorId, datasetVersionId, request);
    }

    public MlDatasetSummaryDto delete(UUID accountId, UUID actorId, UUID datasetVersionId, MlLifecycleActionRequest request) {
        return lifecycleService.delete(accountId, actorId, datasetVersionId, request);
    }
}
