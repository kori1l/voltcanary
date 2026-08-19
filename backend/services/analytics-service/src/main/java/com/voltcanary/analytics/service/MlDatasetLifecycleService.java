package com.voltcanary.analytics.service;

import com.voltcanary.analytics.domain.MlDataset;
import com.voltcanary.analytics.domain.MlDatasetStatus;
import com.voltcanary.analytics.domain.MlDatasetVersion;
import com.voltcanary.analytics.dto.MlDatasetSummaryDto;
import com.voltcanary.analytics.dto.MlLifecycleActionRequest;
import com.voltcanary.analytics.dto.MlLifecycleImpactResponse;
import com.voltcanary.analytics.repository.MlDatasetRepository;
import com.voltcanary.analytics.repository.MlDatasetVersionRepository;
import com.voltcanary.analytics.repository.MlFeatureSnapshotRepository;
import com.voltcanary.analytics.repository.MlLabelRepository;
import com.voltcanary.analytics.repository.MlModelVersionRepository;
import com.voltcanary.analytics.repository.MlTrainingRunRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MlDatasetLifecycleService {

    private final MlDatasetRepository datasetRepository;
    private final MlDatasetVersionRepository datasetVersionRepository;
    private final MlLabelRepository labelRepository;
    private final MlFeatureSnapshotRepository featureSnapshotRepository;
    private final MlTrainingRunRepository trainingRunRepository;
    private final MlModelVersionRepository modelVersionRepository;
    private final MlDatasetResponseMapper mapper;

    public MlDatasetLifecycleService(
            MlDatasetRepository datasetRepository,
            MlDatasetVersionRepository datasetVersionRepository,
            MlLabelRepository labelRepository,
            MlFeatureSnapshotRepository featureSnapshotRepository,
            MlTrainingRunRepository trainingRunRepository,
            MlModelVersionRepository modelVersionRepository,
            MlDatasetResponseMapper mapper) {
        this.datasetRepository = datasetRepository;
        this.datasetVersionRepository = datasetVersionRepository;
        this.labelRepository = labelRepository;
        this.featureSnapshotRepository = featureSnapshotRepository;
        this.trainingRunRepository = trainingRunRepository;
        this.modelVersionRepository = modelVersionRepository;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public MlLifecycleImpactResponse impact(UUID accountId, UUID datasetVersionId, String action) {
        MlDatasetVersion version = findOwnedVersion(accountId, datasetVersionId);
        String normalizedAction = normalizedAction(action);
        List<Map<String, Object>> dependencies = "delete".equals(normalizedAction)
                ? List.of(dependency("datasetVersion", datasetVersionId, Map.copyOf(datasetDependencies(datasetVersionId))))
                : List.of(dependency("datasetVersion", datasetVersionId, Map.of("status", version.getStatus().name())));
        boolean blocked = "delete".equals(normalizedAction)
                && datasetDependencies(datasetVersionId).values().stream().anyMatch(count -> count > 0);
        String message = blocked
                ? "Dataset version is referenced and should be archived instead of deleted"
                : "Dataset version action is allowed when submitted with an audit reason";
        return new MlLifecycleImpactResponse(normalizedAction, blocked, message, dependencies);
    }

    @Transactional
    public MlDatasetSummaryDto archive(UUID accountId, UUID actorId, UUID datasetVersionId, MlLifecycleActionRequest request) {
        MlDatasetVersion version = findOwnedVersion(accountId, datasetVersionId);
        String reason = requiredReason(request);
        if (version.getStatus() == MlDatasetStatus.DELETED) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "ML dataset version not found");
        }
        version.setStatus(MlDatasetStatus.ARCHIVED);
        stampLifecycle(version, actorId, reason);
        datasetVersionRepository.save(version);
        return mapper.summary(dataset(version), version);
    }

    @Transactional
    public MlDatasetSummaryDto delete(UUID accountId, UUID actorId, UUID datasetVersionId, MlLifecycleActionRequest request) {
        MlDatasetVersion version = findOwnedVersion(accountId, datasetVersionId);
        String reason = requiredReason(request);
        if (version.getStatus() == MlDatasetStatus.DELETED) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "ML dataset version not found");
        }
        Map<String, Long> dependencies = datasetDependencies(datasetVersionId);
        if (dependencies.values().stream().anyMatch(count -> count > 0)) {
            throw new MlLifecycleConflictException(
                    "DATASET_REFERENCED",
                    "Dataset version is referenced by ML lineage and must be archived instead of deleted",
                    dependency("datasetVersion", datasetVersionId, Map.copyOf(dependencies)));
        }
        if (version.getStatus() != MlDatasetStatus.FAILED && version.getStatus() != MlDatasetStatus.DRAFT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only failed or draft unreferenced dataset versions can be deleted");
        }
        version.setStatus(MlDatasetStatus.DELETED);
        stampLifecycle(version, actorId, reason);
        datasetVersionRepository.save(version);
        return mapper.summary(dataset(version), version);
    }

    private MlDatasetVersion findOwnedVersion(UUID accountId, UUID datasetVersionId) {
        MlDatasetVersion version = datasetVersionRepository.findById(datasetVersionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ML dataset version not found"));
        if (!accountId.equals(version.getAccountId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "ML dataset version not found");
        }
        return version;
    }

    private MlDataset dataset(MlDatasetVersion version) {
        return datasetRepository.findById(version.getDatasetId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ML dataset not found"));
    }

    private Map<String, Long> datasetDependencies(UUID datasetVersionId) {
        Map<String, Long> dependencies = new LinkedHashMap<>();
        dependencies.put("labels", labelRepository.countByDatasetVersionIdAndDeletedAtIsNull(datasetVersionId));
        dependencies.put("featureSnapshots", featureSnapshotRepository.countByDatasetVersionId(datasetVersionId));
        dependencies.put("trainingRuns", trainingRunRepository.countByDatasetVersionId(datasetVersionId));
        dependencies.put("modelVersions", modelVersionRepository.countByDatasetVersionId(datasetVersionId));
        return dependencies;
    }

    private String requiredReason(MlLifecycleActionRequest request) {
        String value = request == null ? null : request.reason();
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Lifecycle reason is required");
        }
        return value.trim();
    }

    private String normalizedAction(String action) {
        if (action == null || action.isBlank()) {
            return "delete";
        }
        return action.trim().toLowerCase(Locale.ROOT);
    }

    private void stampLifecycle(MlDatasetVersion version, UUID actorId, String reason) {
        version.setLifecycleActorId(actorId);
        version.setLifecycleAt(Instant.now());
        version.setLifecycleReason(reason);
    }

    private Map<String, Object> dependency(String type, UUID id, Map<String, ?> details) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("type", type);
        value.put("id", id.toString());
        value.put("details", details);
        return value;
    }
}
