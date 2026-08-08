package com.voltcanary.analytics.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.analytics.domain.MlDatasetStatus;
import com.voltcanary.analytics.domain.MlDatasetVersion;
import com.voltcanary.analytics.domain.MlFeatureSnapshot;
import com.voltcanary.analytics.domain.MlModelFamily;
import com.voltcanary.analytics.domain.MlTrainingRun;
import com.voltcanary.analytics.domain.MlTrainingRunStatus;
import com.voltcanary.analytics.dto.MlTrainingRunDto;
import com.voltcanary.analytics.dto.MlTrainingRunRequest;
import com.voltcanary.analytics.dto.MlLifecycleActionRequest;
import com.voltcanary.analytics.dto.MlLifecycleImpactResponse;
import com.voltcanary.analytics.repository.MlDatasetVersionRepository;
import com.voltcanary.analytics.repository.MlFeatureSnapshotRepository;
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
public class MlTrainingService {

    private static final String DEFAULT_ANOMALY_ALGORITHM = "sklearn-isolation-forest-v1";
    private static final String DEFAULT_FAILURE_ALGORITHM = "sklearn-logistic-regression-failure-v1";

    private final MlDatasetVersionRepository datasetVersionRepository;
    private final MlFeatureSnapshotRepository featureSnapshotRepository;
    private final MlTrainingRunRepository trainingRunRepository;
    private final MlModelVersionRepository modelVersionRepository;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    public MlTrainingService(
            MlDatasetVersionRepository datasetVersionRepository,
            MlFeatureSnapshotRepository featureSnapshotRepository,
            MlTrainingRunRepository trainingRunRepository,
            MlModelVersionRepository modelVersionRepository) {
        this.datasetVersionRepository = datasetVersionRepository;
        this.featureSnapshotRepository = featureSnapshotRepository;
        this.trainingRunRepository = trainingRunRepository;
        this.modelVersionRepository = modelVersionRepository;
    }

    @Transactional
    public MlTrainingRunDto create(UUID accountId, UUID userId, MlTrainingRunRequest request) {
        MlDatasetVersion datasetVersion = datasetVersionRepository.findById(request.datasetVersionId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ML dataset version not found"));
        if (!accountId.equals(datasetVersion.getAccountId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "ML dataset version not found");
        }
        if (datasetVersion.getStatus() != MlDatasetStatus.READY) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ML dataset version must be READY");
        }
        MlFeatureSnapshot featureSnapshot = featureSnapshotRepository.findById(request.featureSnapshotId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ML feature snapshot not found"));
        if (!datasetVersion.getId().equals(featureSnapshot.getDatasetVersionId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Feature snapshot must belong to the dataset version");
        }
        if (!"ACTIVE".equals(featureSnapshot.getLifecycleStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Feature snapshot must be active");
        }
        validateTrainingRequest(request, featureSnapshot);

        Instant now = Instant.now();
        MlTrainingRun run = new MlTrainingRun();
        run.setId(UUID.randomUUID());
        run.setAccountId(accountId);
        run.setDatasetVersionId(datasetVersion.getId());
        run.setFeatureSnapshotId(featureSnapshot.getId());
        run.setModelFamily(request.modelFamily());
        run.setAlgorithm(resolveAlgorithm(request));
        run.setHyperparametersJson(trainingHyperparameters(request));
        run.setStatus(MlTrainingRunStatus.QUEUED);
        run.setCreatedBy(userId);
        run.setCreatedAt(now);
        trainingRunRepository.save(run);
        return response(run);
    }

    private void validateTrainingRequest(MlTrainingRunRequest request, MlFeatureSnapshot featureSnapshot) {
        if (request.modelFamily() != MlModelFamily.ANOMALY_DETECTION
                && request.modelFamily() != MlModelFamily.FAILURE_PREDICTION) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only ANOMALY_DETECTION and FAILURE_PREDICTION training are implemented");
        }
        if (request.modelFamily() != MlModelFamily.FAILURE_PREDICTION) {
            return;
        }
        if (request.failureHorizonDays() == null || request.failureHorizonDays() < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "FAILURE_PREDICTION training requires failureHorizonDays");
        }
        if (!MlModelFamily.FAILURE_PREDICTION.name().equals(featureSnapshot.getModelFamily())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Failure prediction training requires a FAILURE_PREDICTION feature snapshot");
        }
        if (!request.failureHorizonDays().equals(featureSnapshot.getHorizonDays())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Failure prediction training horizon must match the feature snapshot horizon");
        }
    }

    private String resolveAlgorithm(MlTrainingRunRequest request) {
        if (request.algorithm() != null && !request.algorithm().isBlank()) {
            return request.algorithm();
        }
        return request.modelFamily() == MlModelFamily.FAILURE_PREDICTION
                ? DEFAULT_FAILURE_ALGORITHM
                : DEFAULT_ANOMALY_ALGORITHM;
    }

    public List<MlTrainingRunDto> list(UUID accountId) {
        return list(accountId, "default");
    }

    public List<MlTrainingRunDto> list(UUID accountId, String statusFilter) {
        String filter = normalizedFilter(statusFilter);
        return trainingRunRepository.findByAccountIdOrderByCreatedAtDesc(accountId).stream()
                .filter(run -> matchesFilter(run.getStatus(), filter))
                .map(this::response)
                .toList();
    }

    private boolean matchesFilter(MlTrainingRunStatus status, String filter) {
        return switch (filter) {
            case "all" -> true;
            case "failed" -> status == MlTrainingRunStatus.FAILED || status == MlTrainingRunStatus.CANCELED;
            case "archived" -> status == MlTrainingRunStatus.ARCHIVED;
            case "deleted" -> status == MlTrainingRunStatus.DELETED;
            default -> status != MlTrainingRunStatus.ARCHIVED && status != MlTrainingRunStatus.DELETED;
        };
    }

    private String normalizedFilter(String statusFilter) {
        if (statusFilter == null || statusFilter.isBlank()) {
            return "default";
        }
        return statusFilter.trim().toLowerCase(Locale.ROOT);
    }

    @Transactional
    public MlTrainingRunDto cancel(UUID accountId, UUID runId) {
        MlTrainingRun run = findOwned(accountId, runId);
        if (run.getStatus() != MlTrainingRunStatus.QUEUED && run.getStatus() != MlTrainingRunStatus.RUNNING) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only queued or running training runs can be canceled");
        }
        if (run.getStatus() == MlTrainingRunStatus.RUNNING) {
            run.setCancellationRequested(true);
            run.setLogsSummary("Training run cancellation requested by operator.");
        } else {
            run.setStatus(MlTrainingRunStatus.CANCELED);
            run.setCompletedAt(Instant.now());
            run.setLogsSummary("Training run canceled by operator.");
        }
        trainingRunRepository.save(run);
        return response(run);
    }

    @Transactional
    public MlTrainingRunDto retry(UUID accountId, UUID runId) {
        MlTrainingRun failed = findOwned(accountId, runId);
        if (failed.getStatus() != MlTrainingRunStatus.FAILED && failed.getStatus() != MlTrainingRunStatus.CANCELED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only failed or canceled training runs can be retried");
        }
        MlTrainingRun retry = new MlTrainingRun();
        retry.setId(UUID.randomUUID());
        retry.setAccountId(failed.getAccountId());
        retry.setDatasetVersionId(failed.getDatasetVersionId());
        retry.setFeatureSnapshotId(failed.getFeatureSnapshotId());
        retry.setModelFamily(failed.getModelFamily());
        retry.setAlgorithm(failed.getAlgorithm());
        retry.setHyperparametersJson(failed.getHyperparametersJson());
        retry.setStatus(MlTrainingRunStatus.QUEUED);
        retry.setRetryCount(failed.getRetryCount() + 1);
        retry.setCreatedBy(failed.getCreatedBy());
        retry.setCreatedAt(Instant.now());
        trainingRunRepository.save(retry);
        return response(retry);
    }

    @Transactional
    public MlTrainingRunDto archive(UUID accountId, UUID actorId, UUID runId, MlLifecycleActionRequest request) {
        MlTrainingRun run = findOwned(accountId, runId);
        String reason = requiredReason(request);
        if (run.getStatus() == MlTrainingRunStatus.DELETED) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "ML training run not found");
        }
        if (run.getStatus() == MlTrainingRunStatus.QUEUED || run.getStatus() == MlTrainingRunStatus.RUNNING) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Queued or running training runs must be canceled before archive");
        }
        run.setStatus(MlTrainingRunStatus.ARCHIVED);
        stampLifecycle(run, actorId, reason);
        trainingRunRepository.save(run);
        return response(run);
    }

    @Transactional(readOnly = true)
    public MlLifecycleImpactResponse impact(UUID accountId, UUID runId, String action) {
        MlTrainingRun run = findOwned(accountId, runId);
        String normalizedAction = normalizedAction(action);
        long modelReferences = modelVersionRepository.countByTrainingRunId(runId);
        boolean blocked = "delete".equals(normalizedAction)
                && (modelReferences > 0 || run.getStatus() == MlTrainingRunStatus.SUCCEEDED);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("modelVersions", modelReferences);
        details.put("status", run.getStatus().name());
        String message = blocked
                ? "Training run has model lineage and should be archived instead of deleted"
                : "Training run action is allowed when submitted with an audit reason";
        return new MlLifecycleImpactResponse(
                normalizedAction,
                blocked,
                message,
                List.of(dependency("trainingRun", runId, details)));
    }

    @Transactional
    public MlTrainingRunDto delete(UUID accountId, UUID actorId, UUID runId, MlLifecycleActionRequest request) {
        MlTrainingRun run = findOwned(accountId, runId);
        String reason = requiredReason(request);
        if (run.getStatus() == MlTrainingRunStatus.DELETED) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "ML training run not found");
        }
        long modelReferences = modelVersionRepository.countByTrainingRunId(runId);
        if (modelReferences > 0 || run.getStatus() == MlTrainingRunStatus.SUCCEEDED) {
            throw new MlLifecycleConflictException(
                    "TRAINING_RUN_MODEL_REFERENCED",
                    "Training run registered or is referenced by model lineage and must be archived instead of deleted",
                    dependency("trainingRun", runId, Map.of("modelVersions", modelReferences, "status", run.getStatus().name())));
        }
        if (run.getStatus() != MlTrainingRunStatus.FAILED && run.getStatus() != MlTrainingRunStatus.CANCELED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only failed or canceled unreferenced training runs can be deleted");
        }
        run.setStatus(MlTrainingRunStatus.DELETED);
        stampLifecycle(run, actorId, reason);
        trainingRunRepository.save(run);
        return response(run);
    }

    private MlTrainingRun findOwned(UUID accountId, UUID runId) {
        MlTrainingRun run = trainingRunRepository.findById(runId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ML training run not found"));
        if (!accountId.equals(run.getAccountId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "ML training run not found");
        }
        return run;
    }

    MlTrainingRunDto response(MlTrainingRun run) {
        return new MlTrainingRunDto(
                run.getId(),
                run.getAccountId(),
                run.getDatasetVersionId(),
                run.getFeatureSnapshotId(),
                run.getModelFamily(),
                run.getAlgorithm(),
                run.getHyperparametersJson(),
                run.getStatus(),
                run.getMetricsJson(),
                run.getArtifactUri(),
                run.getLogsSummary(),
                run.getFailureReason(),
                run.getCreatedBy(),
                run.getCreatedAt(),
                run.getStartedAt(),
                run.getCompletedAt(),
                run.getLeaseOwner(),
                run.getLeaseExpiresAt(),
                run.getHeartbeatAt(),
                run.isCancellationRequested(),
                run.getRetryCount(),
                run.getStaleRecoveredAt());
    }

    private String trainingHyperparameters(MlTrainingRunRequest request) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (request.hyperparametersJson() != null && !request.hyperparametersJson().isBlank()) {
            try {
                values.putAll(objectMapper.readValue(request.hyperparametersJson(), new TypeReference<>() {}));
            } catch (JsonProcessingException exception) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "hyperparametersJson must be valid JSON object");
            }
        }
        putIfPresent(values, "failureHorizonDays", request.failureHorizonDays());
        putIfPresent(values, "minimumPositiveLabels", request.minimumPositiveLabels());
        putIfPresent(values, "minimumLabelCoverage", request.minimumLabelCoverage());
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize training hyperparameters", exception);
        }
    }

    private void putIfPresent(Map<String, Object> values, String key, Object value) {
        if (value != null) {
            values.put(key, value);
        }
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

    private void stampLifecycle(MlTrainingRun run, UUID actorId, String reason) {
        run.setLifecycleActorId(actorId);
        run.setLifecycleAt(Instant.now());
        run.setLifecycleReason(reason);
    }

    private Map<String, Object> dependency(String type, UUID id, Map<String, Object> details) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("type", type);
        value.put("id", id.toString());
        value.put("details", details);
        return value;
    }
}
