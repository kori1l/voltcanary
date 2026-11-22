package com.voltcanary.analytics.service;

import com.voltcanary.analytics.domain.MlCanonicalMetricRow;
import com.voltcanary.analytics.domain.MlDatasetStatus;
import com.voltcanary.analytics.domain.MlDatasetVersion;
import com.voltcanary.analytics.domain.MlHorizonLabelGenerationJob;
import com.voltcanary.analytics.domain.MlHorizonLabelGenerationStatus;
import com.voltcanary.analytics.dto.MlHorizonLabelGenerationRequest;
import com.voltcanary.analytics.dto.MlHorizonLabelGenerationResponse;
import com.voltcanary.analytics.dto.MlHorizonLabelGenerationResult;
import com.voltcanary.analytics.repository.MaintenanceOutcomeReadModelRepository;
import com.voltcanary.analytics.repository.MlCanonicalMetricRowRepository;
import com.voltcanary.analytics.repository.MlDatasetVersionRepository;
import com.voltcanary.analytics.repository.MlHorizonLabelGenerationJobRepository;
import com.voltcanary.analytics.repository.MlLabelRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
@lombok.extern.slf4j.Slf4j
public class MlHorizonLabelGenerationService {

    private static final String DEFAULT_GENERATION_VERSION = "failure-outcome-v1";
    private static final List<MlHorizonLabelGenerationStatus> IDEMPOTENT_STATUSES = List.of(
        MlHorizonLabelGenerationStatus.QUEUED,
        MlHorizonLabelGenerationStatus.RUNNING,
        MlHorizonLabelGenerationStatus.COMPLETED
    );

    private final MlDatasetVersionRepository datasetVersionRepository;
    private final MlCanonicalMetricRowRepository canonicalMetricRowRepository;
    private final MlHorizonLabelMaterialization materialization;
    private final MlHorizonLabelPolicy labelPolicy;
    private final MlHorizonLabelGenerationJobRepository jobRepository;
    private final TaskExecutor taskExecutor;
    private final TransactionTemplate transactionTemplate;

    public MlHorizonLabelGenerationService(
        MlDatasetVersionRepository datasetVersionRepository,
        MlCanonicalMetricRowRepository canonicalMetricRowRepository,
        MaintenanceOutcomeReadModelRepository outcomeRepository,
        MaintenanceWindowProjectionService maintenanceWindowProjectionService,
        MlLabelRepository labelRepository,
        MlHorizonLabelGenerationJobRepository jobRepository,
        @Qualifier("applicationTaskExecutor") TaskExecutor taskExecutor,
        PlatformTransactionManager transactionManager
    ) {
        this.datasetVersionRepository = datasetVersionRepository;
        this.canonicalMetricRowRepository = canonicalMetricRowRepository;
        this.labelPolicy = new MlHorizonLabelPolicy(maintenanceWindowProjectionService);
        this.materialization = new MlHorizonLabelMaterialization(
            canonicalMetricRowRepository,
            outcomeRepository,
            labelRepository,
            labelPolicy
        );
        this.jobRepository = jobRepository;
        this.taskExecutor = taskExecutor;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Transactional
    public MlHorizonLabelGenerationResponse start(
        UUID accountId,
        UUID actorId,
        MlHorizonLabelGenerationRequest request
    ) {
        if (request.horizonDays() < 1) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "horizonDays must be positive"
            );
        }
        MlDatasetVersion datasetVersion = datasetVersion(accountId, request.datasetVersionId());
        if (datasetVersion.getStatus() != MlDatasetStatus.READY) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Dataset version must be READY before label generation"
            );
        }
        List<MlCanonicalMetricRow> rows =
            canonicalMetricRowRepository.findByDatasetVersionIdOrderByTimestampAsc(
                datasetVersion.getId()
            );
        if (rows.isEmpty()) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Dataset version has no canonical rows"
            );
        }

        String generationVersion = normalizedGenerationVersion(request.generationVersion());
        String inputLineageHash = inputLineageHash(datasetVersion, rows);
        String idempotencyKey = idempotencyKey(
            accountId,
            datasetVersion.getId(),
            request.horizonDays(),
            generationVersion,
            request.includePreventiveMaintenance(),
            inputLineageHash
        );

        var existing =
            jobRepository.findFirstByAccountIdAndIdempotencyKeyAndStatusInOrderBySubmittedAtDesc(
                accountId,
                idempotencyKey,
                IDEMPOTENT_STATUSES
            );
        if (existing.isPresent()) {
            return dto(existing.get());
        }

        Instant now = Instant.now();
        MlHorizonLabelGenerationJob job = new MlHorizonLabelGenerationJob();
        job.setId(UUID.randomUUID());
        job.setAccountId(accountId);
        job.setDatasetVersionId(datasetVersion.getId());
        job.setSubmittedBy(actorId);
        job.setHorizonDays(request.horizonDays());
        job.setGenerationVersion(generationVersion);
        job.setIncludePreventiveMaintenance(request.includePreventiveMaintenance());
        job.setInputLineageHash(inputLineageHash);
        job.setIdempotencyKey(idempotencyKey);
        job.setStatus(MlHorizonLabelGenerationStatus.QUEUED);
        job.setSubmittedAt(now);
        job.setProgressPercent(0);
        job.setMessage("Queued horizon label generation");
        MlHorizonLabelGenerationJob saved = jobRepository.save(job);
        schedule(saved.getId());
        return dto(saved);
    }

    @Transactional(readOnly = true)
    public MlHorizonLabelGenerationResponse status(UUID accountId, UUID jobId) {
        return dto(
            jobRepository
                .findByIdAndAccountId(jobId, accountId)
                .orElseThrow(() ->
                    new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Horizon label generation job not found"
                    )
                )
        );
    }

    @org.springframework.scheduling.annotation.Scheduled(
        initialDelayString = "${app.ml.horizon-labels.recovery-initial-delay:60s}",
        fixedDelayString = "${app.ml.horizon-labels.recovery-delay:10s}"
    )
    public void recoverQueuedJobs() {
        var due = jobRepository.findByStatusInOrderBySubmittedAtAsc(
            List.of(MlHorizonLabelGenerationStatus.QUEUED, MlHorizonLabelGenerationStatus.RUNNING),
            org.springframework.data.domain.PageRequest.of(0, 10)
        );
        for (var job : due) runJob(job.getId());
    }

    public void runJob(UUID jobId) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                var job = jobRepository.tryLockById(jobId).orElse(null);
                if (
                    job == null ||
                    (job.getStatus() != MlHorizonLabelGenerationStatus.QUEUED &&
                        job.getStatus() != MlHorizonLabelGenerationStatus.RUNNING)
                ) return;
                job.setStatus(MlHorizonLabelGenerationStatus.RUNNING);
                job.setStartedAt(Instant.now());
                job.setProgressPercent(10);
                job.setMessage("Generating horizon labels");
                var result = generateLabels(job);
                // Result replacement and terminal job state are one commit. A crash rolls
                // back to QUEUED.
                job.setStatus(MlHorizonLabelGenerationStatus.COMPLETED);
                job.setCompletedAt(Instant.now());
                job.setProgressPercent(100);
                job.setMessage("Horizon label generation completed");
                job.setFailureReason(null);
                job.setLineageHash(result.lineageHash());
                job.setGeneratedLabels(result.generatedLabels());
                job.setPositiveLabels(result.positiveLabels());
                job.setNormalLabels(result.normalLabels());
                job.setMaintenanceExcludedLabels(result.maintenanceExcludedLabels());
                job.setUnresolvedLabels(result.unresolvedLabels());
                job.setUnobservableRows(result.unobservableRows());
                jobRepository.save(job);
            });
        } catch (Exception exception) {
            transactionTemplate.executeWithoutResult(status ->
                jobRepository.tryLockById(jobId).ifPresent(job -> {
                    if (
                        job.getStatus() != MlHorizonLabelGenerationStatus.QUEUED &&
                        job.getStatus() != MlHorizonLabelGenerationStatus.RUNNING
                    ) return;
                    job.setStatus(MlHorizonLabelGenerationStatus.FAILED);
                    job.setCompletedAt(Instant.now());
                    job.setProgressPercent(100);
                    job.setMessage("Horizon label generation failed");
                    job.setFailureReason(sanitizedFailure(exception));
                    jobRepository.save(job);
                })
            );
        }
    }

    private void schedule(UUID jobId) {
        Runnable command = () -> runJob(jobId);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        dispatch(command);
                    }
                }
            );
        } else {
            dispatch(command);
        }
    }

    private void dispatch(Runnable command) {
        try {
            taskExecutor.execute(command);
        } catch (RuntimeException unavailable) {
            log.warn(
                "horizonLabelDispatchDeferred reasonCode={}",
                unavailable.getClass().getSimpleName()
            );
        }
    }

    private MlHorizonLabelGenerationResult generateLabels(MlHorizonLabelGenerationJob job) {
        return materialization.generateLabels(
            job,
            datasetVersion(job.getAccountId(), job.getDatasetVersionId())
        );
    }

    private MlDatasetVersion datasetVersion(UUID accountId, UUID datasetVersionId) {
        MlDatasetVersion datasetVersion = datasetVersionRepository
            .findById(datasetVersionId)
            .orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Dataset version not found")
            );
        if (!accountId.equals(datasetVersion.getAccountId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Dataset version not found");
        }
        return datasetVersion;
    }

    private MlHorizonLabelGenerationResponse dto(MlHorizonLabelGenerationJob job) {
        MlHorizonLabelGenerationResult result =
            job.getStatus() == MlHorizonLabelGenerationStatus.COMPLETED
                ? new MlHorizonLabelGenerationResult(
                      job.getLineageHash(),
                      safeCount(job.getGeneratedLabels()),
                      safeCount(job.getPositiveLabels()),
                      safeCount(job.getNormalLabels()),
                      safeCount(job.getMaintenanceExcludedLabels()),
                      safeCount(job.getUnresolvedLabels()),
                      safeCount(job.getUnobservableRows())
                  )
                : null;
        return new MlHorizonLabelGenerationResponse(
            job.getId(),
            job.getDatasetVersionId(),
            job.getStatus(),
            job.getSubmittedAt(),
            job.getStartedAt(),
            job.getCompletedAt(),
            job.getProgressPercent(),
            job.getMessage(),
            job.getStatus() == MlHorizonLabelGenerationStatus.FAILED
                ? job.getFailureReason()
                : null,
            job.getHorizonDays(),
            job.getGenerationVersion(),
            job.isIncludePreventiveMaintenance(),
            job.getInputLineageHash(),
            result
        );
    }

    private int safeCount(Integer value) {
        return value == null ? 0 : value;
    }

    private String normalizedGenerationVersion(String value) {
        return value == null || value.isBlank() ? DEFAULT_GENERATION_VERSION : value.trim();
    }

    private String inputLineageHash(
        MlDatasetVersion datasetVersion,
        List<MlCanonicalMetricRow> rows
    ) {
        if (datasetVersion.getDataHash() != null && !datasetVersion.getDataHash().isBlank()) {
            return datasetVersion.getDataHash();
        }
        if (
            datasetVersion.getImmutableRowReference() != null &&
            !datasetVersion.getImmutableRowReference().isBlank()
        ) {
            return labelPolicy.hash(
                datasetVersion.getId() + "|" + datasetVersion.getImmutableRowReference()
            );
        }
        String payload = rows
            .stream()
            .map(
                row ->
                    row.getId() +
                    "|" +
                    row.getTimestamp() +
                    "|" +
                    row.getDeviceId() +
                    "|" +
                    row.getMetricName()
            )
            .sorted()
            .collect(Collectors.joining("\n", datasetVersion.getId() + "\n", ""));
        return labelPolicy.hash(payload);
    }

    private String idempotencyKey(
        UUID accountId,
        UUID datasetVersionId,
        int horizonDays,
        String generationVersion,
        boolean includePreventiveMaintenance,
        String inputLineageHash
    ) {
        return labelPolicy.hash(
            accountId +
                "|" +
                datasetVersionId +
                "|" +
                horizonDays +
                "|" +
                generationVersion +
                "|" +
                includePreventiveMaintenance +
                "|" +
                inputLineageHash
        );
    }

    private String sanitizedFailure(Exception exception) {
        String message =
            exception instanceof ResponseStatusException responseStatusException &&
            responseStatusException.getReason() != null
                ? responseStatusException.getReason()
                : exception.getMessage();
        if (message == null || message.isBlank()) {
            return "Horizon label generation failed";
        }
        String sanitized = message
            .replaceAll("[\\r\\n\\t]+", " ")
            .replaceAll("\\s{2,}", " ")
            .trim();
        return sanitized.substring(0, Math.min(500, sanitized.length()));
    }
}
