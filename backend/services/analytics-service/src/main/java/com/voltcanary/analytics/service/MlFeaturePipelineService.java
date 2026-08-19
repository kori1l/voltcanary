package com.voltcanary.analytics.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.analytics.dto.MlFeatureSnapshotRequest;
import com.voltcanary.analytics.dto.MlFeatureSnapshotResponse;
import com.voltcanary.analytics.dto.MlLifecycleActionRequest;
import com.voltcanary.analytics.dto.MlLifecycleImpactResponse;
import com.voltcanary.analytics.repository.MlCanonicalMetricRowRepository;
import com.voltcanary.analytics.repository.MlDatasetVersionRepository;
import com.voltcanary.analytics.repository.MlFeatureRowRepository;
import com.voltcanary.analytics.repository.MlFeatureSnapshotRepository;
import com.voltcanary.analytics.repository.MlImportedFileRepository;
import com.voltcanary.analytics.repository.MlImportedMetricRowRepository;
import com.voltcanary.analytics.repository.MlLabelRepository;
import com.voltcanary.analytics.repository.MlModelVersionRepository;
import com.voltcanary.analytics.repository.MlTrainingRunRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MlFeaturePipelineService {

    private final MlFeatureSnapshotGenerationService generationService;
    private final MlFeatureSnapshotLifecycleService lifecycleService;
    private final MlFeatureSnapshotRepository featureSnapshotRepository;
    private final MlFeatureSnapshotResponseMapper mapper;

    @Autowired
    public MlFeaturePipelineService(
            MlFeatureSnapshotGenerationService generationService,
            MlFeatureSnapshotLifecycleService lifecycleService,
            MlFeatureSnapshotRepository featureSnapshotRepository,
            MlFeatureSnapshotResponseMapper mapper) {
        this.generationService = generationService;
        this.lifecycleService = lifecycleService;
        this.featureSnapshotRepository = featureSnapshotRepository;
        this.mapper = mapper;
    }

    public MlFeaturePipelineService(
            MlDatasetVersionRepository datasetVersionRepository,
            MlCanonicalMetricRowRepository canonicalMetricRowRepository,
            MlImportedFileRepository importedFileRepository,
            MlImportedMetricRowRepository importedMetricRowRepository,
            MlFeatureSnapshotRepository featureSnapshotRepository,
            MlFeatureRowRepository featureRowRepository,
            MlLabelRepository labelRepository,
            MlTrainingRunRepository trainingRunRepository,
            MlModelVersionRepository modelVersionRepository,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry) {
        MlFeatureTemplateAppender templateAppender = new MlFeatureTemplateAppender();
        MlInternalTelemetryFeatureBuilder internalBuilder = new MlInternalTelemetryFeatureBuilder(
                canonicalMetricRowRepository,
                labelRepository,
                templateAppender);
        MlImportedDatasetFeatureBuilder importedBuilder = new MlImportedDatasetFeatureBuilder(
                importedFileRepository,
                importedMetricRowRepository,
                labelRepository,
                templateAppender);
        MlFeatureGenerationService featureGenerationService = new MlFeatureGenerationService(internalBuilder, importedBuilder);
        MlFeatureHashService hashService = new MlFeatureHashService(objectMapper);
        MlFeatureSnapshotResponseMapper responseMapper = new MlFeatureSnapshotResponseMapper();
        this.generationService = new MlFeatureSnapshotGenerationService(
                datasetVersionRepository,
                featureSnapshotRepository,
                featureRowRepository,
                featureGenerationService,
                hashService,
                responseMapper,
                meterRegistry);
        this.lifecycleService = new MlFeatureSnapshotLifecycleService(
                featureSnapshotRepository,
                trainingRunRepository,
                modelVersionRepository,
                responseMapper);
        this.featureSnapshotRepository = featureSnapshotRepository;
        this.mapper = responseMapper;
    }

    public MlFeatureSnapshotResponse generate(UUID accountId, MlFeatureSnapshotRequest request) {
        return generationService.generate(accountId, request);
    }

    @Transactional(readOnly = true)
    public List<MlFeatureSnapshotResponse> list(UUID accountId, UUID datasetVersionId) {
        return (datasetVersionId == null
                ? featureSnapshotRepository.findByAccountIdAndLifecycleStatusOrderByCreatedAtDesc(accountId, "ACTIVE")
                : featureSnapshotRepository.findByAccountIdAndDatasetVersionIdAndLifecycleStatusOrderByCreatedAtDesc(accountId, datasetVersionId, "ACTIVE"))
                .stream()
                .map(mapper::response)
                .toList();
    }

    public MlFeatureSnapshotResponse archive(UUID accountId, UUID actorId, UUID featureSnapshotId, MlLifecycleActionRequest request) {
        return lifecycleService.archive(accountId, actorId, featureSnapshotId, request);
    }

    public MlLifecycleImpactResponse impact(UUID accountId, UUID featureSnapshotId, String action) {
        return lifecycleService.impact(accountId, featureSnapshotId, action);
    }

    public MlFeatureSnapshotResponse delete(UUID accountId, UUID actorId, UUID featureSnapshotId, MlLifecycleActionRequest request) {
        return lifecycleService.delete(accountId, actorId, featureSnapshotId, request);
    }
}
