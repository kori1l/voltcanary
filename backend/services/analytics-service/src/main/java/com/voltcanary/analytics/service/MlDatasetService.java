package com.voltcanary.analytics.service;

import com.voltcanary.analytics.dto.MlDatasetDetailDto;
import com.voltcanary.analytics.dto.MlDatasetSummaryDto;
import com.voltcanary.analytics.dto.MlDatasetWorkbenchPreviewRequest;
import com.voltcanary.analytics.dto.MlDatasetWorkbenchPreviewResponse;
import com.voltcanary.analytics.dto.MlInternalDatasetRequest;
import com.voltcanary.analytics.dto.MlInternalDatasetResponse;
import com.voltcanary.analytics.dto.MlLifecycleActionRequest;
import com.voltcanary.analytics.dto.MlLifecycleImpactResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class MlDatasetService {

    private final MlDatasetPreviewService previewService;
    private final MlDatasetMaterializationService materializationService;
    private final MlDatasetQueryService queryService;
    private final MlDatasetLifecycleService lifecycleService;

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
