package com.voltcanary.analytics.service;

import com.voltcanary.analytics.domain.MaintenanceOutcomeReadModel;
import com.voltcanary.analytics.domain.MaintenanceWindowReadModel;
import com.voltcanary.analytics.domain.MlCanonicalMetricRow;
import com.voltcanary.analytics.domain.MlDatasetStatus;
import com.voltcanary.analytics.domain.MlDatasetVersion;
import com.voltcanary.analytics.domain.MlHorizonLabelGenerationJob;
import com.voltcanary.analytics.domain.MlHorizonLabelGenerationStatus;
import com.voltcanary.analytics.domain.MlLabel;
import com.voltcanary.analytics.domain.MlLabelSource;
import com.voltcanary.analytics.domain.MlLabelType;
import com.voltcanary.analytics.dto.MlHorizonLabelGenerationRequest;
import com.voltcanary.analytics.dto.MlHorizonLabelGenerationResponse;
import com.voltcanary.analytics.dto.MlHorizonLabelGenerationResult;
import com.voltcanary.analytics.repository.MaintenanceOutcomeReadModelRepository;
import com.voltcanary.analytics.repository.MlCanonicalMetricRowRepository;
import com.voltcanary.analytics.repository.MlDatasetVersionRepository;
import com.voltcanary.analytics.repository.MlHorizonLabelGenerationJobRepository;
import com.voltcanary.analytics.repository.MlLabelRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
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
public class MlHorizonLabelGenerationService {

    private static final String DEFAULT_GENERATION_VERSION = "failure-outcome-v1";
    private static final List<MlHorizonLabelGenerationStatus> IDEMPOTENT_STATUSES = List.of(
            MlHorizonLabelGenerationStatus.QUEUED,
            MlHorizonLabelGenerationStatus.RUNNING,
            MlHorizonLabelGenerationStatus.COMPLETED);

    private final MlDatasetVersionRepository datasetVersionRepository;
    private final MlCanonicalMetricRowRepository canonicalMetricRowRepository;
    private final MaintenanceOutcomeReadModelRepository outcomeRepository;
    private final MaintenanceWindowProjectionService maintenanceWindowProjectionService;
    private final MlLabelRepository labelRepository;
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
            PlatformTransactionManager transactionManager) {
        this.datasetVersionRepository = datasetVersionRepository;
        this.canonicalMetricRowRepository = canonicalMetricRowRepository;
        this.outcomeRepository = outcomeRepository;
        this.maintenanceWindowProjectionService = maintenanceWindowProjectionService;
        this.labelRepository = labelRepository;
        this.jobRepository = jobRepository;
        this.taskExecutor = taskExecutor;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Transactional
    public MlHorizonLabelGenerationResponse start(UUID accountId, UUID actorId, MlHorizonLabelGenerationRequest request) {
        if (request.horizonDays() < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "horizonDays must be positive");
        }
        MlDatasetVersion datasetVersion = datasetVersion(accountId, request.datasetVersionId());
        if (datasetVersion.getStatus() != MlDatasetStatus.READY) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Dataset version must be READY before label generation");
        }
        List<MlCanonicalMetricRow> rows = canonicalMetricRowRepository.findByDatasetVersionIdOrderByTimestampAsc(datasetVersion.getId());
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Dataset version has no canonical rows");
        }

        String generationVersion = normalizedGenerationVersion(request.generationVersion());
        String inputLineageHash = inputLineageHash(datasetVersion, rows);
        String idempotencyKey = idempotencyKey(
                accountId,
                datasetVersion.getId(),
                request.horizonDays(),
                generationVersion,
                request.includePreventiveMaintenance(),
                inputLineageHash);

        var existing = jobRepository.findFirstByAccountIdAndIdempotencyKeyAndStatusInOrderBySubmittedAtDesc(
                accountId,
                idempotencyKey,
                IDEMPOTENT_STATUSES);
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
        return dto(jobRepository.findByIdAndAccountId(jobId, accountId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Horizon label generation job not found")));
    }

    public void runJob(UUID jobId) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                MlHorizonLabelGenerationJob job = jobRepository.findById(jobId).orElseThrow();
                if (job.getStatus() != MlHorizonLabelGenerationStatus.QUEUED) {
                    return;
                }
                job.setStatus(MlHorizonLabelGenerationStatus.RUNNING);
                job.setStartedAt(Instant.now());
                job.setProgressPercent(10);
                job.setMessage("Generating horizon labels");
                jobRepository.save(job);
            });

            MlHorizonLabelGenerationResult result = transactionTemplate.execute(status -> {
                MlHorizonLabelGenerationJob job = jobRepository.findById(jobId).orElseThrow();
                return generateLabels(job);
            });

            transactionTemplate.executeWithoutResult(status -> {
                MlHorizonLabelGenerationJob job = jobRepository.findById(jobId).orElseThrow();
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
            transactionTemplate.executeWithoutResult(status -> jobRepository.findById(jobId).ifPresent(job -> {
                job.setStatus(MlHorizonLabelGenerationStatus.FAILED);
                job.setCompletedAt(Instant.now());
                job.setProgressPercent(100);
                job.setMessage("Horizon label generation failed");
                job.setFailureReason(sanitizedFailure(exception));
                jobRepository.save(job);
            }));
        }
    }

    private void schedule(UUID jobId) {
        Runnable command = () -> runJob(jobId);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    taskExecutor.execute(command);
                }
            });
        } else {
            taskExecutor.execute(command);
        }
    }

    private MlHorizonLabelGenerationResult generateLabels(MlHorizonLabelGenerationJob job) {
        MlDatasetVersion datasetVersion = datasetVersion(job.getAccountId(), job.getDatasetVersionId());
        List<MlCanonicalMetricRow> rows = canonicalMetricRowRepository.findByDatasetVersionIdOrderByTimestampAsc(datasetVersion.getId());
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Dataset version has no canonical rows");
        }
        Instant first = rows.getFirst().getTimestamp();
        Instant observedThrough = rows.stream()
                .map(MlCanonicalMetricRow::getTimestamp)
                .max(Comparator.naturalOrder())
                .orElse(first);
        Map<UUID, Instant> observedThroughByDevice = rows.stream()
                .filter(row -> row.getDeviceId() != null)
                .collect(Collectors.toMap(
                        MlCanonicalMetricRow::getDeviceId,
                        MlCanonicalMetricRow::getTimestamp,
                        (left, right) -> left.isAfter(right) ? left : right));
        Instant horizonEnd = observedThrough.plus(job.getHorizonDays(), ChronoUnit.DAYS);
        Map<UUID, List<MaintenanceOutcomeReadModel>> outcomesByDevice = outcomeRepository
                .findByAccountAndObservedRange(job.getAccountId(), first, horizonEnd)
                .stream()
                .filter(outcome -> outcome.getDeviceId() != null)
                .collect(Collectors.groupingBy(MaintenanceOutcomeReadModel::getDeviceId));

        List<PlannedLabel> planned = rows.stream()
                .map(row -> labelFor(
                        row,
                        outcomesByDevice.getOrDefault(row.getDeviceId(), List.of()),
                        observedThroughByDevice.get(row.getDeviceId()),
                        job))
                .filter(item -> item != null)
                .toList();
        String lineageHash = lineageHash(datasetVersion.getId(), job.getGenerationVersion(), job.getHorizonDays(), planned);

        labelRepository.deleteGeneratedOutcomeLabels(job.getAccountId(), datasetVersion.getId(), job.getHorizonDays());
        Instant now = Instant.now();
        List<MlLabel> labels = planned.stream()
                .map(item -> toLabel(job.getAccountId(), job.getSubmittedBy(), datasetVersion.getId(), job.getGenerationVersion(), lineageHash, now, item))
                .toList();
        labelRepository.saveAll(labels);

        int positives = (int) labels.stream().filter(label -> isPositive(label.getLabel())).count();
        int normals = (int) labels.stream().filter(label -> label.getLabel() == MlLabelType.NORMAL).count();
        int maintenance = (int) labels.stream().filter(label -> label.getLabel() == MlLabelType.MAINTENANCE).count();
        int unresolved = (int) labels.stream().filter(label -> label.getLabel() == MlLabelType.UNRESOLVED_ANOMALY).count();
        int unobservable = rows.size() - labels.size();
        return new MlHorizonLabelGenerationResult(
                lineageHash,
                labels.size(),
                positives,
                normals,
                maintenance,
                unresolved,
                unobservable);
    }

    private MlDatasetVersion datasetVersion(UUID accountId, UUID datasetVersionId) {
        MlDatasetVersion datasetVersion = datasetVersionRepository.findById(datasetVersionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Dataset version not found"));
        if (!accountId.equals(datasetVersion.getAccountId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Dataset version not found");
        }
        return datasetVersion;
    }

    private PlannedLabel labelFor(
            MlCanonicalMetricRow row,
            List<MaintenanceOutcomeReadModel> outcomes,
            Instant observedThroughForDevice,
            MlHorizonLabelGenerationJob job) {
        if (row.getDeviceId() == null) {
            return null;
        }
        Instant predictionAt = row.getTimestamp();
        Instant horizonEnd = predictionAt.plus(job.getHorizonDays(), ChronoUnit.DAYS);
        List<MaintenanceWindowReadModel> maintenanceWindows = maintenanceWindowProjectionService
                .activeOverlapping(row.getDeviceId(), predictionAt, predictionAt.plusMillis(1));
        if (maintenanceWindows.stream().anyMatch(MaintenanceWindowReadModel::isExcludeFromMl)) {
            return new PlannedLabel(row, MlLabelType.MAINTENANCE, List.of(), "maintenance exclusion", job.getHorizonDays());
        }
        MaintenanceOutcomeReadModel selectedPositive = outcomes.stream()
                .filter(outcome -> outcome.getObservedAt().isAfter(predictionAt) && !outcome.getObservedAt().isAfter(horizonEnd))
                .filter(outcome -> isPositive(labelType(outcome, job.isIncludePreventiveMaintenance())))
                .findFirst()
                .orElse(null);
        if (selectedPositive != null) {
            return new PlannedLabel(
                    row,
                    labelType(selectedPositive, job.isIncludePreventiveMaintenance()),
                    List.of(selectedPositive.getMaintenanceOutcomeId()),
                    "maintenance outcome",
                    job.getHorizonDays());
        }
        MaintenanceOutcomeReadModel selectedFeedback = outcomes.stream()
                .filter(outcome -> outcome.getObservedAt().isAfter(predictionAt) && !outcome.getObservedAt().isAfter(horizonEnd))
                .filter(outcome -> labelType(outcome, job.isIncludePreventiveMaintenance()) != null)
                .findFirst()
                .orElse(null);
        if (selectedFeedback != null) {
            return new PlannedLabel(
                    row,
                    labelType(selectedFeedback, job.isIncludePreventiveMaintenance()),
                    List.of(selectedFeedback.getMaintenanceOutcomeId()),
                    "maintenance feedback outcome",
                    job.getHorizonDays());
        }
        if (observedThroughForDevice == null || horizonEnd.isAfter(observedThroughForDevice)) {
            return null;
        }
        return new PlannedLabel(row, MlLabelType.NORMAL, List.of(), "no failure outcome within observable horizon", job.getHorizonDays());
    }

    private MlLabelType labelType(MaintenanceOutcomeReadModel outcome, boolean includePreventiveMaintenance) {
        return switch (outcome.getOutcomeType()) {
            case "CONFIRMED_FAILURE" -> MlLabelType.CONFIRMED_FAILURE;
            case "CORRECTIVE_MAINTENANCE" -> MlLabelType.CORRECTIVE_MAINTENANCE;
            case "PREVENTIVE_MAINTENANCE" -> includePreventiveMaintenance ? MlLabelType.PREVENTIVE_MAINTENANCE : null;
            case "INSPECTION_NORMAL" -> MlLabelType.INSPECTION_NORMAL;
            case "FALSE_POSITIVE" -> MlLabelType.FALSE_POSITIVE;
            case "SENSOR_FAULT" -> MlLabelType.SENSOR_FAULT;
            case "UNRESOLVED_ANOMALY" -> MlLabelType.UNRESOLVED_ANOMALY;
            default -> null;
        };
    }

    private MlLabel toLabel(
            UUID accountId,
            UUID actorId,
            UUID datasetVersionId,
            String generationVersion,
            String lineageHash,
            Instant now,
            PlannedLabel planned) {
        MlLabel label = new MlLabel();
        label.setId(UUID.randomUUID());
        label.setAccountId(accountId);
        label.setDatasetVersionId(datasetVersionId);
        label.setDeviceId(planned.row().getDeviceId());
        label.setExternalDeviceKey(planned.row().getExternalDeviceKey());
        label.setMetricName(planned.row().getMetricName());
        label.setLabelStartAt(planned.row().getTimestamp());
        label.setLabelEndAt(planned.row().getTimestamp().plusMillis(1));
        label.setLabel(planned.label());
        label.setSource(MlLabelSource.MAINTENANCE_OUTCOME);
        label.setReason(planned.reason());
        label.setHorizonDays(planned.horizonDays());
        label.setGenerationVersion(generationVersion);
        label.setSourceOutcomeIdsJson(outcomeIdsJson(planned.sourceOutcomeIds()));
        label.setLineageHash(lineageHash);
        label.setCreatedBy(actorId);
        label.setCreatedAt(now);
        label.setUpdatedAt(now);
        return label;
    }

    private MlHorizonLabelGenerationResponse dto(MlHorizonLabelGenerationJob job) {
        MlHorizonLabelGenerationResult result = job.getStatus() == MlHorizonLabelGenerationStatus.COMPLETED
                ? new MlHorizonLabelGenerationResult(
                        job.getLineageHash(),
                        safeCount(job.getGeneratedLabels()),
                        safeCount(job.getPositiveLabels()),
                        safeCount(job.getNormalLabels()),
                        safeCount(job.getMaintenanceExcludedLabels()),
                        safeCount(job.getUnresolvedLabels()),
                        safeCount(job.getUnobservableRows()))
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
                job.getStatus() == MlHorizonLabelGenerationStatus.FAILED ? job.getFailureReason() : null,
                job.getHorizonDays(),
                job.getGenerationVersion(),
                job.isIncludePreventiveMaintenance(),
                job.getInputLineageHash(),
                result);
    }

    private int safeCount(Integer value) {
        return value == null ? 0 : value;
    }

    private boolean isPositive(MlLabelType label) {
        return label == MlLabelType.CONFIRMED_FAILURE || label == MlLabelType.CORRECTIVE_MAINTENANCE;
    }

    private String normalizedGenerationVersion(String value) {
        return value == null || value.isBlank() ? DEFAULT_GENERATION_VERSION : value.trim();
    }

    private String outcomeIdsJson(List<UUID> ids) {
        return "[" + ids.stream()
                .map(id -> "\"" + id + "\"")
                .collect(Collectors.joining(",")) + "]";
    }

    private String inputLineageHash(MlDatasetVersion datasetVersion, List<MlCanonicalMetricRow> rows) {
        if (datasetVersion.getDataHash() != null && !datasetVersion.getDataHash().isBlank()) {
            return datasetVersion.getDataHash();
        }
        if (datasetVersion.getImmutableRowReference() != null && !datasetVersion.getImmutableRowReference().isBlank()) {
            return hash(datasetVersion.getId() + "|" + datasetVersion.getImmutableRowReference());
        }
        String payload = rows.stream()
                .map(row -> row.getId() + "|" + row.getTimestamp() + "|" + row.getDeviceId() + "|" + row.getMetricName())
                .sorted()
                .collect(Collectors.joining("\n", datasetVersion.getId() + "\n", ""));
        return hash(payload);
    }

    private String idempotencyKey(
            UUID accountId,
            UUID datasetVersionId,
            int horizonDays,
            String generationVersion,
            boolean includePreventiveMaintenance,
            String inputLineageHash) {
        return hash(accountId
                + "|"
                + datasetVersionId
                + "|"
                + horizonDays
                + "|"
                + generationVersion
                + "|"
                + includePreventiveMaintenance
                + "|"
                + inputLineageHash);
    }

    private String lineageHash(UUID datasetVersionId, String generationVersion, int horizonDays, List<PlannedLabel> labels) {
        String payload = labels.stream()
                .map(label -> label.row().getId()
                        + "|"
                        + label.label().name()
                        + "|"
                        + outcomeIdsJson(label.sourceOutcomeIds()))
                .sorted()
                .collect(Collectors.joining("\n", datasetVersionId + "|" + generationVersion + "|" + horizonDays + "\n", ""));
        return hash(payload);
    }

    private String hash(String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return "sha256:" + HexFormat.of().formatHex(digest.digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to hash label lineage", exception);
        }
    }

    private String sanitizedFailure(Exception exception) {
        String message = exception instanceof ResponseStatusException responseStatusException && responseStatusException.getReason() != null
                ? responseStatusException.getReason()
                : exception.getMessage();
        if (message == null || message.isBlank()) {
            return "Horizon label generation failed";
        }
        String sanitized = message.replaceAll("[\\r\\n\\t]+", " ").replaceAll("\\s{2,}", " ").trim();
        return sanitized.substring(0, Math.min(500, sanitized.length()));
    }

    private record PlannedLabel(
            MlCanonicalMetricRow row,
            MlLabelType label,
            List<UUID> sourceOutcomeIds,
            String reason,
            int horizonDays) {
    }
}
