package com.voltcanary.analytics.service;

import com.voltcanary.analytics.domain.MlDatasetStatus;
import com.voltcanary.analytics.domain.MlDatasetVersion;
import com.voltcanary.analytics.domain.MlFeatureSnapshot;
import com.voltcanary.analytics.domain.MlModelFamily;
import com.voltcanary.analytics.domain.MlTrainingRun;
import com.voltcanary.analytics.domain.MlTrainingRunStatus;
import com.voltcanary.analytics.dto.MlTrainingRunDto;
import com.voltcanary.analytics.dto.MlTrainingRunRequest;
import com.voltcanary.analytics.repository.MlDatasetVersionRepository;
import com.voltcanary.analytics.repository.MlFeatureSnapshotRepository;
import com.voltcanary.analytics.repository.MlTrainingRunRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MlTrainingService {

    private final MlDatasetVersionRepository datasetVersionRepository;
    private final MlFeatureSnapshotRepository featureSnapshotRepository;
    private final MlTrainingRunRepository trainingRunRepository;

    public MlTrainingService(
            MlDatasetVersionRepository datasetVersionRepository,
            MlFeatureSnapshotRepository featureSnapshotRepository,
            MlTrainingRunRepository trainingRunRepository) {
        this.datasetVersionRepository = datasetVersionRepository;
        this.featureSnapshotRepository = featureSnapshotRepository;
        this.trainingRunRepository = trainingRunRepository;
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
        if (request.modelFamily() != MlModelFamily.ANOMALY_DETECTION) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only ANOMALY_DETECTION training is implemented");
        }

        Instant now = Instant.now();
        MlTrainingRun run = new MlTrainingRun();
        run.setId(UUID.randomUUID());
        run.setAccountId(accountId);
        run.setDatasetVersionId(datasetVersion.getId());
        run.setFeatureSnapshotId(featureSnapshot.getId());
        run.setModelFamily(request.modelFamily());
        run.setAlgorithm(request.algorithm() == null || request.algorithm().isBlank()
                ? "baseline-statistical-anomaly-v1"
                : request.algorithm());
        run.setHyperparametersJson(request.hyperparametersJson() == null || request.hyperparametersJson().isBlank()
                ? "{}"
                : request.hyperparametersJson());
        run.setStatus(MlTrainingRunStatus.QUEUED);
        run.setCreatedBy(userId);
        run.setCreatedAt(now);
        trainingRunRepository.save(run);
        return response(run);
    }

    public List<MlTrainingRunDto> list(UUID accountId) {
        return trainingRunRepository.findByAccountIdOrderByCreatedAtDesc(accountId).stream()
                .map(this::response)
                .toList();
    }

    @Transactional
    public MlTrainingRunDto cancel(UUID accountId, UUID runId) {
        MlTrainingRun run = findOwned(accountId, runId);
        if (run.getStatus() != MlTrainingRunStatus.QUEUED && run.getStatus() != MlTrainingRunStatus.RUNNING) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only queued or running training runs can be canceled");
        }
        run.setStatus(MlTrainingRunStatus.CANCELED);
        run.setCompletedAt(Instant.now());
        run.setLogsSummary("Training run canceled by operator.");
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
        retry.setCreatedBy(failed.getCreatedBy());
        retry.setCreatedAt(Instant.now());
        trainingRunRepository.save(retry);
        return response(retry);
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
                run.getCompletedAt());
    }
}
