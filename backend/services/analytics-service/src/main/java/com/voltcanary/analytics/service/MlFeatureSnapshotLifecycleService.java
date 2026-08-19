package com.voltcanary.analytics.service;

import com.voltcanary.analytics.domain.MlFeatureSnapshot;
import com.voltcanary.analytics.dto.MlFeatureSnapshotResponse;
import com.voltcanary.analytics.dto.MlLifecycleActionRequest;
import com.voltcanary.analytics.dto.MlLifecycleImpactResponse;
import com.voltcanary.analytics.repository.MlFeatureSnapshotRepository;
import com.voltcanary.analytics.repository.MlModelVersionRepository;
import com.voltcanary.analytics.repository.MlTrainingRunRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MlFeatureSnapshotLifecycleService {

    private final MlFeatureSnapshotRepository featureSnapshotRepository;
    private final MlTrainingRunRepository trainingRunRepository;
    private final MlModelVersionRepository modelVersionRepository;
    private final MlFeatureSnapshotResponseMapper mapper;

    public MlFeatureSnapshotLifecycleService(
            MlFeatureSnapshotRepository featureSnapshotRepository,
            MlTrainingRunRepository trainingRunRepository,
            MlModelVersionRepository modelVersionRepository,
            MlFeatureSnapshotResponseMapper mapper) {
        this.featureSnapshotRepository = featureSnapshotRepository;
        this.trainingRunRepository = trainingRunRepository;
        this.modelVersionRepository = modelVersionRepository;
        this.mapper = mapper;
    }

    @Transactional
    public MlFeatureSnapshotResponse archive(UUID accountId, UUID actorId, UUID featureSnapshotId, MlLifecycleActionRequest request) {
        MlFeatureSnapshot snapshot = findOwnedSnapshot(accountId, featureSnapshotId);
        String reason = requiredReason(request);
        if ("DELETED".equals(snapshot.getLifecycleStatus())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "ML feature snapshot not found");
        }
        stampLifecycle(snapshot, "ARCHIVED", actorId, reason);
        featureSnapshotRepository.save(snapshot);
        return mapper.response(snapshot);
    }

    @Transactional(readOnly = true)
    public MlLifecycleImpactResponse impact(UUID accountId, UUID featureSnapshotId, String action) {
        MlFeatureSnapshot snapshot = findOwnedSnapshot(accountId, featureSnapshotId);
        String normalizedAction = normalizedAction(action);
        Map<String, Long> dependencies = featureSnapshotDependencies(featureSnapshotId);
        boolean blocked = "delete".equals(normalizedAction)
                && dependencies.values().stream().anyMatch(count -> count > 0);
        List<Map<String, Object>> dependencySummary = "delete".equals(normalizedAction)
                ? List.of(dependency("featureSnapshot", featureSnapshotId, dependencies))
                : List.of(dependency("featureSnapshot", featureSnapshotId, Map.of("status", snapshot.getLifecycleStatus())));
        String message = blocked
                ? "Feature snapshot is referenced and should be archived instead of deleted"
                : "Feature snapshot action is allowed when submitted with an audit reason";
        return new MlLifecycleImpactResponse(normalizedAction, blocked, message, dependencySummary);
    }

    @Transactional
    public MlFeatureSnapshotResponse delete(UUID accountId, UUID actorId, UUID featureSnapshotId, MlLifecycleActionRequest request) {
        MlFeatureSnapshot snapshot = findOwnedSnapshot(accountId, featureSnapshotId);
        String reason = requiredReason(request);
        if ("DELETED".equals(snapshot.getLifecycleStatus())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "ML feature snapshot not found");
        }
        Map<String, Long> dependencies = featureSnapshotDependencies(featureSnapshotId);
        if (dependencies.values().stream().anyMatch(count -> count > 0)) {
            throw new MlLifecycleConflictException(
                    "FEATURE_SNAPSHOT_REFERENCED",
                    "Feature snapshot is referenced by training or model lineage and must be archived instead of deleted",
                    dependency("featureSnapshot", featureSnapshotId, dependencies));
        }
        stampLifecycle(snapshot, "DELETED", actorId, reason);
        featureSnapshotRepository.save(snapshot);
        return mapper.response(snapshot);
    }

    private Map<String, Long> featureSnapshotDependencies(UUID featureSnapshotId) {
        Map<String, Long> dependencies = new LinkedHashMap<>();
        dependencies.put("trainingRuns", trainingRunRepository.countByFeatureSnapshotId(featureSnapshotId));
        dependencies.put("modelVersions", modelVersionRepository.countByFeatureSnapshotId(featureSnapshotId));
        return dependencies;
    }

    private MlFeatureSnapshot findOwnedSnapshot(UUID accountId, UUID featureSnapshotId) {
        MlFeatureSnapshot snapshot = featureSnapshotRepository.findById(featureSnapshotId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ML feature snapshot not found"));
        if (!accountId.equals(snapshot.getAccountId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "ML feature snapshot not found");
        }
        return snapshot;
    }

    private String requiredReason(MlLifecycleActionRequest request) {
        String value = request == null ? null : request.reason();
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Lifecycle reason is required");
        }
        return value.trim();
    }

    private void stampLifecycle(MlFeatureSnapshot snapshot, String status, UUID actorId, String reason) {
        snapshot.setLifecycleStatus(status);
        snapshot.setLifecycleActorId(actorId);
        snapshot.setLifecycleAt(Instant.now());
        snapshot.setLifecycleReason(reason);
    }

    private Map<String, Object> dependency(String type, UUID id, Map<String, ?> details) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("type", type);
        value.put("id", id.toString());
        value.put("details", details);
        return value;
    }

    private String normalizedAction(String action) {
        if (action == null || action.isBlank()) {
            return "delete";
        }
        return action.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
