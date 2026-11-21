package com.voltcanary.analytics.service;

import com.voltcanary.analytics.dto.MlFeatureSnapshotRequest;
import com.voltcanary.analytics.dto.MlFeatureSnapshotResponse;
import com.voltcanary.analytics.dto.MlLifecycleActionRequest;
import com.voltcanary.analytics.dto.MlLifecycleImpactResponse;
import com.voltcanary.analytics.repository.MlFeatureSnapshotRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MlFeaturePipelineService {

    private final MlFeatureSnapshotGenerationService generationService;
    private final MlFeatureSnapshotLifecycleService lifecycleService;
    private final MlFeatureSnapshotRepository featureSnapshotRepository;
    private final MlFeatureSnapshotResponseMapper mapper;

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
