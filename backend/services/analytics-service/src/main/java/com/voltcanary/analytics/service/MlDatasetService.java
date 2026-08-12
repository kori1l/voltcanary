package com.voltcanary.analytics.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.analytics.domain.MaintenanceOutcomeReadModel;
import com.voltcanary.analytics.domain.MaintenanceWindowReadModel;
import com.voltcanary.analytics.domain.MlCanonicalMetricRow;
import com.voltcanary.analytics.domain.MlDataset;
import com.voltcanary.analytics.domain.MlDatasetLineage;
import com.voltcanary.analytics.domain.MlDatasetSourceType;
import com.voltcanary.analytics.domain.MlDatasetStatus;
import com.voltcanary.analytics.domain.MlDatasetVersion;
import com.voltcanary.analytics.domain.MlLabelType;
import com.voltcanary.analytics.domain.MlModelFamily;
import com.voltcanary.analytics.domain.MlTrainingSplitStrategy;
import com.voltcanary.analytics.domain.MlValidationReport;
import com.voltcanary.analytics.dto.MlCandidateWindowKind;
import com.voltcanary.analytics.dto.MlDatasetCandidateWindowDto;
import com.voltcanary.analytics.dto.MlDatasetDetailDto;
import com.voltcanary.analytics.dto.MlDatasetLabelReadinessDto;
import com.voltcanary.analytics.dto.MlDatasetMetricCoverageDto;
import com.voltcanary.analytics.dto.MlDatasetPreviewCreateDefaultsDto;
import com.voltcanary.analytics.dto.MlDatasetQualityGateDto;
import com.voltcanary.analytics.dto.MlDatasetSummaryDto;
import com.voltcanary.analytics.dto.MlDatasetTimelineBucketDto;
import com.voltcanary.analytics.dto.MlDatasetWindowDto;
import com.voltcanary.analytics.dto.MlDatasetWorkbenchPreviewRequest;
import com.voltcanary.analytics.dto.MlDatasetWorkbenchPreviewResponse;
import com.voltcanary.analytics.dto.MlInternalDatasetRequest;
import com.voltcanary.analytics.dto.MlInternalDatasetResponse;
import com.voltcanary.analytics.dto.MlLifecycleActionRequest;
import com.voltcanary.analytics.dto.MlLifecycleImpactResponse;
import com.voltcanary.analytics.dto.MlLineageDto;
import com.voltcanary.analytics.dto.MlMetricSelectionMode;
import com.voltcanary.analytics.dto.MlValidationReportDto;
import com.voltcanary.analytics.dto.MlWorkbenchGateStatus;
import com.voltcanary.analytics.dto.MlWorkbenchStatus;
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
import com.voltcanary.analytics.telemetry.TelemetryChartResponse;
import com.voltcanary.analytics.telemetry.TelemetryClient;
import com.voltcanary.analytics.telemetry.TelemetryMetricResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.Comparator;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MlDatasetService {

    private static final Logger log = LoggerFactory.getLogger(MlDatasetService.class);

    private static final int DEFAULT_MIN_SAMPLES_PER_METRIC = 24;
    private static final long DEFAULT_MAX_SAMPLING_GAP_SECONDS = 6 * 60 * 60;
    private static final Duration PREVIEW_TTL = Duration.ofMinutes(15);
    private static final String PREVIEW_ALGORITHM_VERSION = "ml-dataset-workbench-preview-v1";

    private final MlDatasetRepository datasetRepository;
    private final MlDatasetVersionRepository datasetVersionRepository;
    private final MlValidationReportRepository validationReportRepository;
    private final MlDatasetLineageRepository lineageRepository;
    private final MlLabelRepository labelRepository;
    private final MlFeatureSnapshotRepository featureSnapshotRepository;
    private final MlTrainingRunRepository trainingRunRepository;
    private final MlModelVersionRepository modelVersionRepository;
    private final MlCanonicalMetricRowRepository canonicalMetricRowRepository;
    private final MaintenanceOutcomeReadModelRepository maintenanceOutcomeRepository;
    private final MaintenanceWindowReadModelRepository maintenanceWindowRepository;
    private final TelemetryClient telemetryClient;
    private final ObjectMapper objectMapper;

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
        this.datasetRepository = datasetRepository;
        this.datasetVersionRepository = datasetVersionRepository;
        this.validationReportRepository = validationReportRepository;
        this.lineageRepository = lineageRepository;
        this.labelRepository = labelRepository;
        this.featureSnapshotRepository = featureSnapshotRepository;
        this.trainingRunRepository = trainingRunRepository;
        this.modelVersionRepository = modelVersionRepository;
        this.canonicalMetricRowRepository = canonicalMetricRowRepository;
        this.maintenanceOutcomeRepository = maintenanceOutcomeRepository;
        this.maintenanceWindowRepository = maintenanceWindowRepository;
        this.telemetryClient = telemetryClient;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public MlDatasetWorkbenchPreviewResponse previewInternalTelemetryDataset(
            UUID accountId,
            MlDatasetWorkbenchPreviewRequest request) {
        validatePreviewRequest(request);
        MlMetricSelectionMode metricSelectionMode = normalizedMetricSelectionMode(request.metricSelectionMode());
        MlModelFamily modelFamily = normalizedModelFamily(request.modelFamily());
        List<String> selectedMetrics = normalizedMetrics(request.selectedMetrics());
        List<UUID> deviceIds = normalizedDeviceIds(request.deviceIds());
        int minSamples = minSamplesPerMetric(request.minSamplesPerMetric());
        long maxGapSeconds = maxSamplingGapSeconds(request.maxSamplingGapSeconds());

        List<MlDatasetMetricCoverageDto> availableMetrics = availableMetrics(
                deviceIds,
                request.from(),
                request.to(),
                metricSelectionMode,
                minSamples,
                maxGapSeconds);

        MlInternalDatasetRequest validationRequest = internalRequestFromPreview(request, selectedMetrics);
        ValidationResult validation = validateTelemetry(validationRequest);
        MlDatasetLabelReadinessDto labelReadiness = labelReadiness(
                accountId,
                modelFamily,
                request.horizonDays(),
                request.from(),
                request.to(),
                validation);
        PreviewDecision decision = previewDecision(
                validationRequest,
                modelFamily,
                request.horizonDays(),
                metricSelectionMode,
                availableMetrics,
                validation,
                labelReadiness);

        String previewHash = previewHash(accountId, validationRequest, validation, labelReadiness);
        List<MlDatasetCandidateWindowDto> candidates = candidateWindows(
                accountId,
                request,
                selectedMetrics,
                modelFamily,
                metricSelectionMode,
                availableMetrics,
                minSamples,
                maxGapSeconds,
                validation,
                labelReadiness,
                decision,
                candidateLimit(request.candidateLimit()));
        String recommendedCandidateId = candidates.stream()
                .filter(MlDatasetCandidateWindowDto::recommended)
                .map(MlDatasetCandidateWindowDto::id)
                .findFirst()
                .orElse(null);

        return new MlDatasetWorkbenchPreviewResponse(
                previewId(previewHash),
                previewHash,
                Instant.now().plus(PREVIEW_TTL),
                decision.status(),
                recommendedCandidateId,
                new MlDatasetWindowDto(request.from(), request.to(), MlCandidateWindowKind.USER_SELECTED),
                availableMetrics,
                candidates,
                timeline(accountId, request, validation),
                labelReadiness,
                decision.qualityGates(),
                decision.blockingReasons(),
                decision.warningReasons(),
                new MlDatasetPreviewCreateDefaultsDto(
                        selectedMetrics,
                        request.from(),
                        request.to(),
                        normalizedSplitStrategy(request.splitStrategy()),
                        featureConfigVersion(request.featureConfigVersion()),
                        minSamples,
                        maxGapSeconds,
                        modelFamily,
                        request.horizonDays(),
                        metricSelectionMode));
    }

    @Transactional
    public MlInternalDatasetResponse createFromInternalTelemetry(
            UUID accountId,
            UUID userId,
            MlInternalDatasetRequest request) {
        validateRequest(request);
        ValidationResult validation = validateTelemetry(request);
        assertPreviewFresh(accountId, request, validation);

        Instant now = Instant.now();
        MlDataset dataset = new MlDataset();
        dataset.setId(UUID.randomUUID());
        dataset.setAccountId(accountId);
        dataset.setName(request.name().trim());
        dataset.setDescription(request.description());
        dataset.setSourceType(MlDatasetSourceType.INTERNAL_TELEMETRY);
        dataset.setCreatedBy(userId);
        dataset.setCreatedAt(now);
        dataset.setUpdatedAt(now);
        datasetRepository.save(dataset);

        MlDatasetVersion version = new MlDatasetVersion();
        version.setId(UUID.randomUUID());
        version.setDatasetId(dataset.getId());
        version.setAccountId(accountId);
        version.setVersion(1);
        version.setSourceType(MlDatasetSourceType.INTERNAL_TELEMETRY);
        version.setStatus(validation.ready() ? MlDatasetStatus.VALIDATING : MlDatasetStatus.FAILED);
        version.setSplitStrategy(request.splitStrategy() == null ? MlTrainingSplitStrategy.TIME_BASED : request.splitStrategy());
        version.setSplitConfigJson(blankToDefault(request.splitConfigJson(), "{\"train\":0.7,\"validation\":0.15,\"test\":0.15}"));
        version.setFeatureConfigVersion(blankToDefault(request.featureConfigVersion(), "feature-config-v1"));
        version.setSourceWindowStart(request.from());
        version.setSourceWindowEnd(request.to());
        version.setCreatedBy(userId);
        version.setCreatedAt(now);
        version.setReadyAt(null);
        version.setFailureReason(validation.ready() ? null : String.join("; ", validation.messages()));
        datasetVersionRepository.save(version);
        if (validation.ready()) {
            try {
                canonicalMetricRowRepository.saveAll(canonicalRows(accountId, version.getId(), request, validation.extractedRows(), now));
                version.setStatus(MlDatasetStatus.READY);
                version.setReadyAt(now);
                version.setImmutableRowReference(immutableRowReference(version.getId()));
                version.setDataHash(dataHash(validation.extractedRows()));
                datasetVersionRepository.save(version);
            } catch (RuntimeException exception) {
                version.setStatus(MlDatasetStatus.FAILED);
                version.setReadyAt(null);
                version.setFailureReason("Internal telemetry materialization failed.");
                version.setImmutableRowReference(null);
                version.setDataHash(null);
                datasetVersionRepository.save(version);
            }
        }

        MlValidationReport report = new MlValidationReport();
        report.setId(UUID.randomUUID());
        report.setDatasetVersionId(version.getId());
        report.setAcceptedRows(validation.acceptedRows());
        report.setRejectedRows(validation.rejectedRows());
        report.setWarningCount(validation.warningCount());
        report.setRejectedReasonsJson(writeJson(validation.rejectedReasons()));
        report.setMappingStatsJson(writeJson(validation.mappingStats()));
        report.setQualityProfileJson(writeJson(qualityProfileWithPreview(accountId, request, validation)));
        report.setSummary(version.getStatus() == MlDatasetStatus.READY
                ? "Telemetry selection is ready for training."
                : "Telemetry selection failed validation.");
        report.setCreatedAt(now);
        validationReportRepository.save(report);

        MlDatasetLineage lineage = new MlDatasetLineage();
        lineage.setId(UUID.randomUUID());
        lineage.setDatasetVersionId(version.getId());
        lineage.setSourceType(MlDatasetSourceType.INTERNAL_TELEMETRY);
        lineage.setSourceReference("telemetry-service:/telemetry/devices/{deviceId}/readings");
        lineage.setFilterJson(writeJson(lineageFilter(request, validation)));
        lineage.setSelectedDevicesJson(writeJson(request.deviceIds()));
        lineage.setSelectedMetricsJson(writeJson(request.metrics()));
        lineage.setSourceWindowStart(request.from());
        lineage.setSourceWindowEnd(request.to());
        lineage.setContentHash(sha256(lineage.getFilterJson() + lineage.getSelectedDevicesJson() + lineage.getSelectedMetricsJson()));
        lineage.setCreatedAt(now);
        lineageRepository.save(lineage);
        log.info("mlDatasetCreated datasetId={} datasetVersionId={} accountId={} sourceType={} status={} acceptedRows={} rejectedRows={} warningCount={}",
                dataset.getId(), version.getId(), accountId, version.getSourceType(), version.getStatus(),
                report.getAcceptedRows(), report.getRejectedRows(), report.getWarningCount());

        return new MlInternalDatasetResponse(
                dataset.getId(),
                version.getId(),
                accountId,
                version.getVersion(),
                version.getStatus(),
                version.getSplitStrategy(),
                report.getAcceptedRows(),
                report.getRejectedRows(),
                report.getWarningCount(),
                validation.messages(),
                version.getCreatedAt(),
                version.getReadyAt());
    }

    @Transactional(readOnly = true)
    public List<MlDatasetSummaryDto> list(UUID accountId) {
        return list(accountId, "default");
    }

    @Transactional(readOnly = true)
    public List<MlDatasetSummaryDto> list(UUID accountId, String statusFilter) {
        String filter = normalizedFilter(statusFilter);
        if (!"default".equals(filter) && !"active".equals(filter) && !"current".equals(filter)) {
            return datasetRepository.findByAccountIdOrderByCreatedAtDesc(accountId).stream()
                    .flatMap(dataset -> datasetVersionRepository.findByDatasetIdOrderByVersionDesc(dataset.getId()).stream()
                            .filter(version -> matchesFilter(version.getStatus(), filter))
                            .map(version -> summary(dataset, version)))
                    .toList();
        }
        return datasetRepository.findByAccountIdOrderByCreatedAtDesc(accountId).stream()
                .map(dataset -> datasetVersionRepository.findByDatasetIdAndStatusNotOrderByVersionDesc(dataset.getId(), MlDatasetStatus.DELETED).stream()
                        .filter(version -> version.getStatus() != MlDatasetStatus.ARCHIVED)
                        .findFirst()
                        .map(version -> summary(dataset, version)))
                .flatMap(Optional::stream)
                .toList();
    }

    private boolean matchesFilter(MlDatasetStatus status, String filter) {
        return switch (filter) {
            case "all" -> true;
            case "failed" -> status == MlDatasetStatus.FAILED;
            case "archived" -> status == MlDatasetStatus.ARCHIVED;
            case "deleted" -> status == MlDatasetStatus.DELETED;
            default -> status != MlDatasetStatus.ARCHIVED && status != MlDatasetStatus.DELETED;
        };
    }

    private String normalizedFilter(String statusFilter) {
        if (statusFilter == null || statusFilter.isBlank()) {
            return "default";
        }
        return statusFilter.trim().toLowerCase(Locale.ROOT);
    }

    @Transactional(readOnly = true)
    public MlDatasetDetailDto detail(UUID accountId, UUID datasetVersionId) {
        MlDatasetVersion version = datasetVersionRepository.findById(datasetVersionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ML dataset version not found"));
        if (!accountId.equals(version.getAccountId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "ML dataset version not found");
        }
        MlDataset dataset = datasetRepository.findById(version.getDatasetId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ML dataset not found"));
        return new MlDatasetDetailDto(
                summary(dataset, version),
                validationReportRepository.findByDatasetVersionIdOrderByCreatedAtDesc(version.getId()).stream()
                        .map(this::validationReport)
                        .toList(),
                lineageRepository.findByDatasetVersionId(version.getId()).stream()
                        .map(this::lineage)
                        .toList());
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
        return summary(dataset(version), version);
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
        return summary(dataset(version), version);
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

    private void validateRequest(MlInternalDatasetRequest request) {
        if (!request.to().isAfter(request.from())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Dataset time window must end after it starts");
        }
        if (request.metrics().stream().anyMatch(metric -> metric == null || metric.isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Metrics must not be blank");
        }
        if (request.deviceIds().stream().distinct().count() != request.deviceIds().size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Device ids must be unique");
        }
    }

    private ValidationResult validateTelemetry(MlInternalDatasetRequest request) {
        int minSamples = request.minSamplesPerMetric() == null ? DEFAULT_MIN_SAMPLES_PER_METRIC : request.minSamplesPerMetric();
        long maxGapSeconds = request.maxSamplingGapSeconds() == null
                ? DEFAULT_MAX_SAMPLING_GAP_SECONDS
                : request.maxSamplingGapSeconds();
        MlMetricSelectionMode metricSelectionMode = normalizedMetricSelectionMode(request.metricSelectionMode());
        long acceptedRows = 0;
        long rejectedRows = 0;
        long warnings = 0;
        long maxSamplingGapObserved = 0;
        long duplicateTimestamps = 0;
        long inspectedPoints = 0;
        List<String> messages = new ArrayList<>();
        Map<String, Long> rejectedReasons = new LinkedHashMap<>();
        Map<String, Long> mappingStats = new LinkedHashMap<>();
        Map<String, Long> coverageByDeviceMetric = new LinkedHashMap<>();
        Map<UUID, List<UUID>> maintenanceWindowIdsByDevice = new LinkedHashMap<>();
        List<ExtractedTelemetryRow> extractedRows = new ArrayList<>();

        for (UUID deviceId : normalizedDeviceIds(request.deviceIds())) {
            List<MaintenanceWindowReadModel> maintenanceWindows =
                    maintenanceWindowRepository.findActiveOverlapping(deviceId, request.from(), request.to());
            if (!maintenanceWindows.isEmpty()) {
                maintenanceWindowIdsByDevice.put(deviceId, maintenanceWindows.stream()
                        .map(MaintenanceWindowReadModel::getMaintenanceWindowId)
                        .toList());
                warnings++;
                messages.add("Device " + deviceId + " overlaps " + maintenanceWindows.size() + " maintenance windows.");
            }
            for (String metric : request.metrics()) {
                TelemetryChartResponse readings;
                try {
                    readings = telemetryClient.readings(deviceId, metric, request.from(), request.to());
                } catch (ResponseStatusException exception) {
                    if (exception.getStatusCode().value() != HttpStatus.NOT_FOUND.value()) {
                        throw exception;
                    }
                    if (metricSelectionMode == MlMetricSelectionMode.ALL_AVAILABLE) {
                        warnings++;
                        messages.add("Device " + deviceId + " metric " + metric
                                + " is unavailable in all-available metric mode.");
                        continue;
                    }
                    rejectedRows += minSamples;
                    increment(rejectedReasons, "UNSUPPORTED_OR_UNAVAILABLE_METRIC");
                    messages.add("Device " + deviceId + " metric " + metric
                            + " is unsupported or unavailable in the selected window.");
                    continue;
                }
                List<TelemetryChartResponse.Point> points = readings == null || readings.points() == null
                        ? List.of()
                        : readings.points().stream()
                                .sorted(Comparator.comparing(TelemetryChartResponse.Point::timestamp))
                                .toList();
                inspectedPoints += points.size();
                duplicateTimestamps += duplicateTimestampCount(points);
                long nonFiniteValues = points.stream().filter(point -> !Double.isFinite(point.value())).count();
                if (nonFiniteValues > 0) {
                    rejectedRows += nonFiniteValues;
                    increment(rejectedReasons, "NON_FINITE_VALUES", nonFiniteValues);
                    messages.add("Device " + deviceId + " metric " + metric + " contains "
                            + nonFiniteValues + " non-finite values.");
                    continue;
                }
                if (points.size() < minSamples) {
                    rejectedRows += minSamples - points.size();
                    increment(rejectedReasons, "INSUFFICIENT_SAMPLES");
                    messages.add("Device " + deviceId + " metric " + metric + " has " + points.size()
                            + " samples; minimum is " + minSamples + ".");
                    continue;
                }
                long maxGap = maxGapSeconds(points);
                if (maxGap > maxGapSeconds) {
                    warnings++;
                    messages.add("Device " + deviceId + " metric " + metric + " max sampling gap is "
                            + maxGap + " seconds.");
                }
                maxSamplingGapObserved = Math.max(maxSamplingGapObserved, maxGap);
                acceptedRows += points.size();
                increment(mappingStats, "accepted:" + metric, points.size());
                coverageByDeviceMetric.put(deviceId + ":" + metric, (long) points.size());
                points.stream()
                        .sorted(Comparator.comparing(TelemetryChartResponse.Point::timestamp))
                        .map(point -> {
                            NormalizedMetric normalized = normalizeMetric(metric);
                            return new ExtractedTelemetryRow(
                                    deviceId,
                                    metric,
                                    normalized.canonicalMetricName(),
                                    normalized.rawUnit(),
                                    normalized.canonicalUnit(),
                                    point.timestamp(),
                                    point.value());
                        })
                        .forEach(extractedRows::add);
            }
        }
        if (acceptedRows == 0 && rejectedReasons.isEmpty()) {
            increment(rejectedReasons, "NO_MATCHING_TELEMETRY");
            rejectedRows += minSamples;
            messages.add("No telemetry rows matched the requested dataset filters.");
        }
        Map<String, Object> qualityProfile = new LinkedHashMap<>();
        qualityProfile.put("rowCount", acceptedRows);
        qualityProfile.put("rejectedRows", rejectedRows);
        qualityProfile.put("deviceCount", request.deviceIds().stream().distinct().count());
        qualityProfile.put("metricCount", request.metrics().stream().distinct().count());
        qualityProfile.put("timeRange", Map.of("from", request.from().toString(), "to", request.to().toString()));
        qualityProfile.put("coverageByDeviceMetric", coverageByDeviceMetric);
        qualityProfile.put("maxSamplingGapSeconds", maxSamplingGapObserved);
        qualityProfile.put("duplicateTimestampRate", inspectedPoints == 0 ? 0.0 : (double) duplicateTimestamps / inspectedPoints);
        qualityProfile.put("nonFiniteImpossibleValueCount", rejectedReasons.getOrDefault("NON_FINITE_VALUES", 0L));
        qualityProfile.put("unitNormalizationWarnings", 0L);
        qualityProfile.put("maintenanceOverlapCount", maintenanceWindowIdsByDevice.values().stream().mapToLong(List::size).sum());
        qualityProfile.put("labelCoverage", Map.of());
        qualityProfile.put("metricSelectionMode", metricSelectionMode.name());
        return new ValidationResult(acceptedRows > 0 && rejectedReasons.isEmpty(),
                acceptedRows,
                rejectedRows,
                warnings,
                List.copyOf(messages),
                rejectedReasons,
                mappingStats,
                maintenanceWindowIdsByDevice,
                qualityProfile,
                extractedRows.stream()
                        .sorted(Comparator
                                .comparing(ExtractedTelemetryRow::deviceId)
                                .thenComparing(ExtractedTelemetryRow::metricName)
                                .thenComparing(ExtractedTelemetryRow::timestamp))
                        .toList());
    }

    private void validatePreviewRequest(MlDatasetWorkbenchPreviewRequest request) {
        if (!request.to().isAfter(request.from())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Dataset preview time window must end after it starts");
        }
        if (normalizedDeviceIds(request.deviceIds()).size() != request.deviceIds().size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Device ids must be unique");
        }
    }

    private MlInternalDatasetRequest internalRequestFromPreview(
            MlDatasetWorkbenchPreviewRequest request,
            List<String> selectedMetrics) {
        return new MlInternalDatasetRequest(
                "preview",
                null,
                normalizedDeviceIds(request.deviceIds()),
                request.deviceType(),
                request.deviceMetadata(),
                selectedMetrics,
                request.from(),
                request.to(),
                normalizedSplitStrategy(request.splitStrategy()),
                request.splitConfigJson(),
                featureConfigVersion(request.featureConfigVersion()),
                minSamplesPerMetric(request.minSamplesPerMetric()),
                maxSamplingGapSeconds(request.maxSamplingGapSeconds()),
                null,
                null,
                normalizedModelFamily(request.modelFamily()),
                request.horizonDays(),
                normalizedMetricSelectionMode(request.metricSelectionMode()));
    }

    private List<MlDatasetMetricCoverageDto> availableMetrics(
            List<UUID> deviceIds,
            Instant from,
            Instant to,
            MlMetricSelectionMode metricSelectionMode,
            int minSamples,
            long maxGapSeconds) {
        Map<UUID, Map<String, MetricMetadata>> metricsByDevice = metricMetadataByDevice(deviceIds);
        Set<String> metricNames = metricNamesForMode(metricsByDevice.values(), metricSelectionMode);
        return metricNames.stream()
                .sorted()
                .map(metricName -> metricCoverage(metricName, deviceIds, metricsByDevice, from, to, metricSelectionMode, minSamples, maxGapSeconds))
                .toList();
    }

    private Map<UUID, Map<String, MetricMetadata>> metricMetadataByDevice(List<UUID> deviceIds) {
        Map<UUID, Map<String, MetricMetadata>> value = new LinkedHashMap<>();
        for (UUID deviceId : deviceIds) {
            Map<String, MetricMetadata> metrics = new LinkedHashMap<>();
            for (TelemetryMetricResponse metric : telemetryClient.metrics(deviceId)) {
                if (metric.name() == null || metric.name().isBlank()) {
                    continue;
                }
                NormalizedMetric normalized = normalizeMetric(metric.name());
                metrics.put(metric.name(), new MetricMetadata(
                        metric.name(),
                        blankToDefault(metric.displayName(), metric.name()),
                        normalized.canonicalMetricName(),
                        metric.unit() == null || metric.unit().isBlank() ? normalized.canonicalUnit() : metric.unit()));
            }
            value.put(deviceId, metrics);
        }
        return value;
    }

    private Set<String> metricNamesForMode(
            Collection<Map<String, MetricMetadata>> metricsByDevice,
            MlMetricSelectionMode metricSelectionMode) {
        LinkedHashSet<String> metricNames = new LinkedHashSet<>();
        boolean first = true;
        for (Map<String, MetricMetadata> deviceMetrics : metricsByDevice) {
            if (metricSelectionMode == MlMetricSelectionMode.COMMON) {
                if (first) {
                    metricNames.addAll(deviceMetrics.keySet());
                    first = false;
                } else {
                    metricNames.retainAll(deviceMetrics.keySet());
                }
            } else {
                metricNames.addAll(deviceMetrics.keySet());
            }
        }
        return metricNames;
    }

    private MlDatasetMetricCoverageDto metricCoverage(
            String metricName,
            List<UUID> deviceIds,
            Map<UUID, Map<String, MetricMetadata>> metricsByDevice,
            Instant from,
            Instant to,
            MlMetricSelectionMode metricSelectionMode,
            int minSamples,
            long maxGapSeconds) {
        MetricMetadata metadata = metricsByDevice.values().stream()
                .map(metrics -> metrics.get(metricName))
                .filter(item -> item != null)
                .findFirst()
                .orElseGet(() -> {
                    NormalizedMetric normalized = normalizeMetric(metricName);
                    return new MetricMetadata(metricName, metricName, normalized.canonicalMetricName(), normalized.canonicalUnit());
                });
        int devicesWithMetric = 0;
        long sampleCount = 0;
        long maxGapObserved = 0;
        long duplicateTimestamps = 0;
        long nonFinite = 0;
        List<String> blockingReasons = new ArrayList<>();
        List<String> warningReasons = new ArrayList<>();
        for (UUID deviceId : deviceIds) {
            if (!metricsByDevice.getOrDefault(deviceId, Map.of()).containsKey(metricName)) {
                continue;
            }
            devicesWithMetric++;
            TelemetryChartResponse readings = telemetryClient.readings(deviceId, metricName, from, to);
            List<TelemetryChartResponse.Point> points = readings == null || readings.points() == null
                    ? List.of()
                    : readings.points().stream()
                            .sorted(Comparator.comparing(TelemetryChartResponse.Point::timestamp))
                            .toList();
            sampleCount += points.size();
            maxGapObserved = Math.max(maxGapObserved, maxGapSeconds(points));
            duplicateTimestamps += duplicateTimestampCount(points);
            nonFinite += points.stream().filter(point -> !Double.isFinite(point.value())).count();
        }
        double coverageRatio = deviceIds.isEmpty() ? 0.0 : (double) devicesWithMetric / deviceIds.size();
        double duplicateRate = sampleCount == 0 ? 0.0 : (double) duplicateTimestamps / sampleCount;
        if (devicesWithMetric == 0) {
            blockingReasons.add("Metric is not available on selected devices.");
        }
        if (metricSelectionMode == MlMetricSelectionMode.COMMON && devicesWithMetric < deviceIds.size()) {
            blockingReasons.add("Metric is not present on every selected device.");
        } else if (metricSelectionMode == MlMetricSelectionMode.ALL_AVAILABLE && devicesWithMetric < deviceIds.size()) {
            warningReasons.add("Metric is available on only " + devicesWithMetric + " of " + deviceIds.size() + " selected devices.");
        }
        if (sampleCount == 0) {
            warningReasons.add("Metric has no samples in the selected window.");
        }
        if (sampleCount > 0 && sampleCount < (long) minSamples * Math.max(1, devicesWithMetric)) {
            blockingReasons.add("Metric has fewer than " + minSamples + " samples per covered device.");
        }
        if (maxGapObserved > maxGapSeconds) {
            warningReasons.add("Metric max sampling gap exceeds " + maxGapSeconds + " seconds.");
        }
        if (nonFinite > 0) {
            blockingReasons.add("Metric contains non-finite values.");
        }
        MlWorkbenchStatus status = blockingReasons.isEmpty()
                ? warningReasons.isEmpty() ? MlWorkbenchStatus.READY : MlWorkbenchStatus.READY_WITH_WARNINGS
                : sampleCount == 0 ? MlWorkbenchStatus.NO_DATA : MlWorkbenchStatus.BLOCKED;
        return new MlDatasetMetricCoverageDto(
                metricName,
                metadata.displayName(),
                metadata.canonicalMetricName(),
                metadata.unit(),
                devicesWithMetric,
                deviceIds.size(),
                coverageRatio,
                sampleCount,
                maxGapObserved,
                duplicateRate,
                nonFinite,
                0,
                status,
                List.copyOf(blockingReasons),
                List.copyOf(warningReasons));
    }

    private MlDatasetLabelReadinessDto labelReadiness(
            UUID accountId,
            MlModelFamily modelFamily,
            Integer horizonDays,
            Instant from,
            Instant to,
            ValidationResult validation) {
        if (modelFamily != MlModelFamily.FAILURE_PREDICTION) {
            return null;
        }
        if (horizonDays == null || horizonDays < 1) {
            return new MlDatasetLabelReadinessDto(
                    horizonDays,
                    0,
                    0,
                    0,
                    validation.extractedRows().size(),
                    0,
                    0.0,
                    MlWorkbenchStatus.BLOCKED);
        }
        List<ExtractedTelemetryRow> rows = validation.extractedRows();
        if (rows.isEmpty()) {
            return new MlDatasetLabelReadinessDto(
                    horizonDays,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0.0,
                    MlWorkbenchStatus.BLOCKED);
        }
        Map<UUID, Instant> observedThroughByDevice = new LinkedHashMap<>();
        for (ExtractedTelemetryRow row : rows) {
            observedThroughByDevice.merge(
                    row.deviceId(),
                    row.timestamp(),
                    (left, right) -> left.isAfter(right) ? left : right);
        }
        Instant horizonEnd = to.plus(Duration.ofDays(horizonDays));
        Map<UUID, List<MaintenanceOutcomeReadModel>> outcomesByDevice = new LinkedHashMap<>();
        for (MaintenanceOutcomeReadModel outcome : maintenanceOutcomeRepository.findByAccountAndObservedRange(accountId, from, horizonEnd)) {
            outcomesByDevice.computeIfAbsent(outcome.getDeviceId(), ignored -> new ArrayList<>()).add(outcome);
        }
        Map<UUID, List<MaintenanceWindowReadModel>> windowsByDevice = new LinkedHashMap<>();
        for (ExtractedTelemetryRow row : rows) {
            windowsByDevice.computeIfAbsent(row.deviceId(), deviceId ->
                    maintenanceWindowRepository.findActiveOverlapping(deviceId, from, to));
        }

        long positive = 0;
        long normal = 0;
        long unresolved = 0;
        long unobservable = 0;
        long maintenanceExcluded = 0;
        for (ExtractedTelemetryRow row : rows) {
            if (isMlExcluded(row, windowsByDevice.getOrDefault(row.deviceId(), List.of()))) {
                maintenanceExcluded++;
                continue;
            }
            Instant rowHorizonEnd = row.timestamp().plus(Duration.ofDays(horizonDays));
            List<MaintenanceOutcomeReadModel> outcomes = outcomesByDevice.getOrDefault(row.deviceId(), List.of());
            MaintenanceOutcomeReadModel positiveOutcome = outcomes.stream()
                    .filter(outcome -> outcome.getObservedAt().isAfter(row.timestamp()) && !outcome.getObservedAt().isAfter(rowHorizonEnd))
                    .filter(outcome -> isPositive(labelType(outcome)))
                    .findFirst()
                    .orElse(null);
            if (positiveOutcome != null) {
                positive++;
                continue;
            }
            boolean unresolvedOutcome = outcomes.stream()
                    .filter(outcome -> outcome.getObservedAt().isAfter(row.timestamp()) && !outcome.getObservedAt().isAfter(rowHorizonEnd))
                    .map(this::labelType)
                    .anyMatch(label -> label == MlLabelType.UNRESOLVED_ANOMALY || label == MlLabelType.SENSOR_FAULT || label == MlLabelType.UNKNOWN);
            if (unresolvedOutcome) {
                unresolved++;
                continue;
            }
            Instant observedThrough = observedThroughByDevice.get(row.deviceId());
            if (observedThrough == null || rowHorizonEnd.isAfter(observedThrough)) {
                unobservable++;
                continue;
            }
            normal++;
        }
        long labeled = positive + normal + unresolved + maintenanceExcluded;
        double coverageRatio = rows.isEmpty() ? 0.0 : (double) labeled / rows.size();
        MlWorkbenchStatus status = positive == 0
                ? MlWorkbenchStatus.BLOCKED
                : coverageRatio < 0.8 || normal == 0
                        ? MlWorkbenchStatus.READY_WITH_WARNINGS
                        : MlWorkbenchStatus.READY;
        return new MlDatasetLabelReadinessDto(
                horizonDays,
                positive,
                normal,
                unresolved,
                unobservable,
                maintenanceExcluded,
                coverageRatio,
                status);
    }

    private PreviewDecision previewDecision(
            MlInternalDatasetRequest request,
            MlModelFamily modelFamily,
            Integer horizonDays,
            MlMetricSelectionMode metricSelectionMode,
            List<MlDatasetMetricCoverageDto> availableMetrics,
            ValidationResult validation,
            MlDatasetLabelReadinessDto labelReadiness) {
        List<String> blockingReasons = new ArrayList<>();
        List<String> warningReasons = new ArrayList<>();
        List<MlDatasetQualityGateDto> qualityGates = new ArrayList<>();
        Map<String, MlDatasetMetricCoverageDto> coverageByMetric = new LinkedHashMap<>();
        for (MlDatasetMetricCoverageDto metric : availableMetrics) {
            coverageByMetric.put(metric.metricName(), metric);
        }

        if (request.metrics().isEmpty()) {
            blockingReasons.add("Select at least one telemetry metric.");
        }
        if (availableMetrics.isEmpty()) {
            blockingReasons.add("Selected devices expose no telemetry metrics.");
        }
        long selectedMetricsWithCoverage = request.metrics().stream()
                .filter(metric -> coverageByMetric.containsKey(metric))
                .count();
        for (String metric : request.metrics()) {
            MlDatasetMetricCoverageDto coverage = coverageByMetric.get(metric);
            if (coverage == null) {
                blockingReasons.add("Selected metric " + metric + " is not available for the selected scope.");
                continue;
            }
            if (metricSelectionMode == MlMetricSelectionMode.COMMON && coverage.devicesWithMetric() < coverage.selectedDeviceCount()) {
                blockingReasons.add("Selected metric " + metric + " is not common to every selected device.");
            }
            if (metricSelectionMode == MlMetricSelectionMode.ALL_AVAILABLE && coverage.devicesWithMetric() < coverage.selectedDeviceCount()) {
                warningReasons.add("Selected metric " + metric + " has partial device coverage.");
            }
            blockingReasons.addAll(coverage.blockingReasons());
            warningReasons.addAll(coverage.warningReasons());
        }
        if (!validation.ready()) {
            blockingReasons.addAll(validation.messages());
        } else if (validation.warningCount() > 0) {
            warningReasons.addAll(validation.messages());
        }

        qualityGates.add(new MlDatasetQualityGateDto(
                "selectedMetrics",
                "Selected metrics",
                request.metrics().isEmpty() ? MlWorkbenchGateStatus.BLOCK : MlWorkbenchGateStatus.PASS,
                request.metrics().size(),
                1,
                true,
                request.metrics().isEmpty() ? "Select at least one telemetry metric." : "Metrics are selected."));
        qualityGates.add(new MlDatasetQualityGateDto(
                "metricCoverage",
                "Metric coverage",
                selectedMetricsWithCoverage == request.metrics().size()
                        ? metricSelectionMode == MlMetricSelectionMode.ALL_AVAILABLE
                                && request.metrics().stream()
                                        .map(coverageByMetric::get)
                                        .filter(metric -> metric != null)
                                        .anyMatch(metric -> metric.devicesWithMetric() < metric.selectedDeviceCount())
                                        ? MlWorkbenchGateStatus.WARN
                                        : MlWorkbenchGateStatus.PASS
                        : MlWorkbenchGateStatus.BLOCK,
                selectedMetricsWithCoverage,
                request.metrics().size(),
                true,
                selectedMetricsWithCoverage == request.metrics().size()
                        ? "Selected metrics have telemetry coverage."
                        : "One or more selected metrics are unavailable."));
        qualityGates.add(new MlDatasetQualityGateDto(
                "minSamplesPerMetric",
                "Minimum samples per metric",
                validation.ready() ? MlWorkbenchGateStatus.PASS : MlWorkbenchGateStatus.BLOCK,
                validation.acceptedRows(),
                (long) minSamplesPerMetric(request.minSamplesPerMetric()) * Math.max(1, request.metrics().size()),
                true,
                validation.ready() ? "Selected metrics have enough samples." : "Selected metrics do not meet minimum sample gates."));
        long observedGap = longQualityValue(validation.qualityProfile(), "maxSamplingGapSeconds");
        qualityGates.add(new MlDatasetQualityGateDto(
                "maxSamplingGapSeconds",
                "Maximum sampling gap",
                observedGap > maxSamplingGapSeconds(request.maxSamplingGapSeconds()) ? MlWorkbenchGateStatus.WARN : MlWorkbenchGateStatus.PASS,
                observedGap,
                maxSamplingGapSeconds(request.maxSamplingGapSeconds()),
                false,
                observedGap > maxSamplingGapSeconds(request.maxSamplingGapSeconds())
                        ? "Selected telemetry has sampling gaps above the warning threshold."
                        : "Sampling gaps are within the configured threshold."));
        if (modelFamily == MlModelFamily.FAILURE_PREDICTION) {
            MlWorkbenchGateStatus labelGateStatus;
            String labelMessage;
            if (horizonDays == null || horizonDays < 1) {
                labelGateStatus = MlWorkbenchGateStatus.BLOCK;
                labelMessage = "Failure prediction horizonDays must be positive.";
                blockingReasons.add(labelMessage);
            } else if (labelReadiness == null || labelReadiness.positiveLabels() == 0) {
                labelGateStatus = MlWorkbenchGateStatus.BLOCK;
                labelMessage = "Failure prediction preview has no confirmed failure or corrective maintenance labels.";
                blockingReasons.add(labelMessage);
            } else if (labelReadiness.status() == MlWorkbenchStatus.READY_WITH_WARNINGS) {
                labelGateStatus = MlWorkbenchGateStatus.WARN;
                labelMessage = "Failure label readiness has weak coverage or class balance.";
                warningReasons.add(labelMessage);
            } else {
                labelGateStatus = MlWorkbenchGateStatus.PASS;
                labelMessage = "Failure label readiness meets preview gates.";
            }
            qualityGates.add(new MlDatasetQualityGateDto(
                    "failureLabelReadiness",
                    "Failure label readiness",
                    labelGateStatus,
                    labelReadiness == null ? 0 : labelReadiness.positiveLabels(),
                    1,
                    true,
                    labelMessage));
        }

        List<String> distinctBlocks = distinct(blockingReasons);
        List<String> distinctWarnings = distinct(warningReasons);
        MlWorkbenchStatus status = distinctBlocks.isEmpty()
                ? distinctWarnings.isEmpty() ? MlWorkbenchStatus.READY : MlWorkbenchStatus.READY_WITH_WARNINGS
                : MlWorkbenchStatus.BLOCKED;
        return new PreviewDecision(status, distinctBlocks, distinctWarnings, List.copyOf(qualityGates));
    }

    private List<MlDatasetCandidateWindowDto> candidateWindows(
            UUID accountId,
            MlDatasetWorkbenchPreviewRequest request,
            List<String> selectedMetrics,
            MlModelFamily modelFamily,
            MlMetricSelectionMode metricSelectionMode,
            List<MlDatasetMetricCoverageDto> availableMetrics,
            int minSamples,
            long maxGapSeconds,
            ValidationResult selectedValidation,
            MlDatasetLabelReadinessDto selectedLabelReadiness,
            PreviewDecision selectedDecision,
            int candidateLimit) {
        List<CandidateDraft> drafts = new ArrayList<>();
        drafts.add(candidateDraft(
                accountId,
                request,
                selectedMetrics,
                modelFamily,
                metricSelectionMode,
                availableMetrics,
                minSamples,
                maxGapSeconds,
                MlCandidateWindowKind.USER_SELECTED,
                request.from(),
                request.to(),
                selectedValidation,
                selectedLabelReadiness,
                selectedDecision,
                "User selected window."));
        if (!selectedMetrics.isEmpty() && drafts.size() < candidateLimit) {
            drafts.add(candidateDraft(
                    accountId,
                    request,
                    selectedMetrics,
                    modelFamily,
                    metricSelectionMode,
                    availableMetrics,
                    minSamples,
                    maxGapSeconds,
                    MlCandidateWindowKind.BEST_COVERAGE,
                    request.from(),
                    request.to(),
                    null,
                    null,
                    null,
                    "Highest coverage in the searched range."));
            Instant recentFrom = recentWindowStart(request.from(), request.to());
            if (recentFrom.isBefore(request.to()) && drafts.size() < candidateLimit) {
                drafts.add(candidateDraft(
                        accountId,
                        request,
                        selectedMetrics,
                        modelFamily,
                        metricSelectionMode,
                        availableMetrics,
                        minSamples,
                        maxGapSeconds,
                        MlCandidateWindowKind.RECENT_STABLE,
                        recentFrom,
                        request.to(),
                        null,
                        null,
                        null,
                        "Most recent stable telemetry window."));
            }
            if (modelFamily == MlModelFamily.FAILURE_PREDICTION && drafts.size() < candidateLimit) {
                TimeWindow failureWindow = failureRichWindow(accountId, request, selectedMetrics);
                drafts.add(candidateDraft(
                        accountId,
                        request,
                        selectedMetrics,
                        modelFamily,
                        metricSelectionMode,
                        availableMetrics,
                        minSamples,
                        maxGapSeconds,
                        MlCandidateWindowKind.FAILURE_RICH,
                        failureWindow.from(),
                        failureWindow.to(),
                        null,
                        null,
                        null,
                        failureWindow.explanation()));
            }
        }
        double bestScore = drafts.stream()
                .filter(draft -> draft.status() != MlWorkbenchStatus.BLOCKED)
                .mapToDouble(CandidateDraft::score)
                .max()
                .orElse(drafts.stream().mapToDouble(CandidateDraft::score).max().orElse(0.0));
        return drafts.stream()
                .limit(candidateLimit)
                .map(draft -> new MlDatasetCandidateWindowDto(
                        candidateId(draft.kind()),
                        draft.kind(),
                        draft.from(),
                        draft.to(),
                        draft.score(),
                        draft.status(),
                        Double.compare(draft.score(), bestScore) == 0 && draft.status() != MlWorkbenchStatus.BLOCKED,
                        draft.sampleCount(),
                        request.deviceIds().size(),
                        selectedMetrics.size(),
                        draft.coverageRatio(),
                        draft.maxSamplingGapSeconds(),
                        draft.positiveLabels(),
                        draft.normalLabels(),
                        draft.unobservableLabels(),
                        draft.maintenanceExcludedLabels(),
                        draft.blockingReasons(),
                        draft.warningReasons(),
                        draft.explanation()))
                .toList();
    }

    private CandidateDraft candidateDraft(
            UUID accountId,
            MlDatasetWorkbenchPreviewRequest request,
            List<String> selectedMetrics,
            MlModelFamily modelFamily,
            MlMetricSelectionMode metricSelectionMode,
            List<MlDatasetMetricCoverageDto> availableMetrics,
            int minSamples,
            long maxGapSeconds,
            MlCandidateWindowKind kind,
            Instant from,
            Instant to,
            ValidationResult existingValidation,
            MlDatasetLabelReadinessDto existingLabelReadiness,
            PreviewDecision existingDecision,
            String explanation) {
        MlInternalDatasetRequest candidateRequest = new MlInternalDatasetRequest(
                "preview",
                null,
                normalizedDeviceIds(request.deviceIds()),
                request.deviceType(),
                request.deviceMetadata(),
                selectedMetrics,
                from,
                to,
                normalizedSplitStrategy(request.splitStrategy()),
                request.splitConfigJson(),
                featureConfigVersion(request.featureConfigVersion()),
                minSamples,
                maxGapSeconds,
                null,
                null,
                modelFamily,
                request.horizonDays(),
                metricSelectionMode);
        ValidationResult validation = existingValidation == null ? validateTelemetry(candidateRequest) : existingValidation;
        MlDatasetLabelReadinessDto labelReadiness = existingLabelReadiness == null
                ? labelReadiness(accountId, modelFamily, request.horizonDays(), from, to, validation)
                : existingLabelReadiness;
        PreviewDecision decision = existingDecision == null
                ? previewDecision(candidateRequest, modelFamily, request.horizonDays(), metricSelectionMode, availableMetrics, validation, labelReadiness)
                : existingDecision;
        double coverageRatio = coverageRatio(validation, request.deviceIds().size(), selectedMetrics.size());
        return new CandidateDraft(
                kind,
                from,
                to,
                candidateScore(validation, labelReadiness, request.deviceIds().size(), selectedMetrics.size(), minSamples, maxGapSeconds),
                decision.status(),
                validation.acceptedRows(),
                coverageRatio,
                longQualityValue(validation.qualityProfile(), "maxSamplingGapSeconds"),
                labelReadiness == null ? 0 : labelReadiness.positiveLabels(),
                labelReadiness == null ? 0 : labelReadiness.normalLabels(),
                labelReadiness == null ? 0 : labelReadiness.unobservableRows(),
                labelReadiness == null ? 0 : labelReadiness.maintenanceExcludedLabels(),
                decision.blockingReasons(),
                decision.warningReasons(),
                explanation);
    }

    private TimeWindow failureRichWindow(UUID accountId, MlDatasetWorkbenchPreviewRequest request, List<String> selectedMetrics) {
        int horizonDays = request.horizonDays() == null || request.horizonDays() < 1 ? 1 : request.horizonDays();
        Set<UUID> selectedDevices = new LinkedHashSet<>(request.deviceIds());
        MaintenanceOutcomeReadModel positive = maintenanceOutcomeRepository
                .findByAccountAndObservedRange(accountId, request.from(), request.to().plus(Duration.ofDays(horizonDays)))
                .stream()
                .filter(outcome -> selectedDevices.contains(outcome.getDeviceId()))
                .filter(outcome -> isPositive(labelType(outcome)))
                .findFirst()
                .orElse(null);
        if (positive == null || selectedMetrics.isEmpty()) {
            return new TimeWindow(request.from(), request.to(), "No failure-rich outcome was found in the searched range.");
        }
        Instant from = maxInstant(request.from(), positive.getObservedAt().minus(Duration.ofDays(horizonDays)));
        Instant to = minInstant(request.to(), positive.getObservedAt().plusSeconds(1));
        if (!to.isAfter(from)) {
            return new TimeWindow(request.from(), request.to(), "Failure-rich outcome exists, but it does not produce a valid candidate range.");
        }
        return new TimeWindow(from, to, "Window includes confirmed failure or corrective maintenance evidence.");
    }

    private Instant recentWindowStart(Instant from, Instant to) {
        Duration range = Duration.between(from, to);
        Duration recent = range.compareTo(Duration.ofDays(7)) > 0 ? Duration.ofDays(7) : range.dividedBy(2);
        return maxInstant(from, to.minus(recent));
    }

    private List<MlDatasetTimelineBucketDto> timeline(
            UUID accountId,
            MlDatasetWorkbenchPreviewRequest request,
            ValidationResult validation) {
        Instant from = request.from();
        Instant to = request.to();
        if (!to.isAfter(from)) {
            return List.of();
        }
        List<UUID> selectedDeviceIds = normalizedDeviceIds(request.deviceIds());
        Map<UUID, List<MaintenanceWindowReadModel>> windowsByDevice = new LinkedHashMap<>();
        for (UUID deviceId : selectedDeviceIds) {
            List<MaintenanceWindowReadModel> windows = maintenanceWindowRepository.findActiveOverlapping(deviceId, from, to);
            windowsByDevice.put(deviceId, windows == null ? List.of() : windows);
        }
        Set<UUID> selectedDevices = new LinkedHashSet<>(selectedDeviceIds);
        List<MaintenanceOutcomeReadModel> outcomeRows = maintenanceOutcomeRepository.findByAccountAndObservedRange(accountId, from, to);
        List<MaintenanceOutcomeReadModel> outcomes = (outcomeRows == null ? List.<MaintenanceOutcomeReadModel>of() : outcomeRows).stream()
                .filter(outcome -> selectedDevices.contains(outcome.getDeviceId()))
                .toList();
        int bucketCount = 12;
        long totalMillis = Math.max(1, Duration.between(from, to).toMillis());
        long bucketMillis = Math.max(1, totalMillis / bucketCount);
        List<MlDatasetTimelineBucketDto> buckets = new ArrayList<>();
        for (int index = 0; index < bucketCount; index++) {
            Instant bucketStart = from.plusMillis(bucketMillis * index);
            Instant bucketEnd = index == bucketCount - 1 ? to : from.plusMillis(bucketMillis * (index + 1));
            long samples = validation.extractedRows().stream()
                    .filter(row -> !row.timestamp().isBefore(bucketStart) && row.timestamp().isBefore(bucketEnd))
                    .count();
            List<String> events = timelineEvents(bucketStart, bucketEnd, index == bucketCount - 1, windowsByDevice, outcomes);
            buckets.add(new MlDatasetTimelineBucketDto(
                    bucketStart,
                    bucketEnd,
                    samples,
                    samples == 0 ? 0.0 : 1.0,
                    samples == 0 ? MlWorkbenchStatus.NO_DATA : MlWorkbenchStatus.READY,
                    events));
        }
        return buckets;
    }

    private List<String> timelineEvents(
            Instant bucketStart,
            Instant bucketEnd,
            boolean lastBucket,
            Map<UUID, List<MaintenanceWindowReadModel>> windowsByDevice,
            List<MaintenanceOutcomeReadModel> outcomes) {
        List<String> events = new ArrayList<>();
        for (List<MaintenanceWindowReadModel> windows : windowsByDevice.values()) {
            for (MaintenanceWindowReadModel window : windows) {
                if (windowOverlaps(bucketStart, bucketEnd, window)) {
                    events.add(window.isExcludeFromMl() ? "MAINTENANCE_EXCLUDED" : "MAINTENANCE_OVERLAP");
                }
            }
        }
        for (MaintenanceOutcomeReadModel outcome : outcomes) {
            Instant observedAt = outcome.getObservedAt();
            boolean inBucket = lastBucket
                    ? !observedAt.isBefore(bucketStart) && !observedAt.isAfter(bucketEnd)
                    : !observedAt.isBefore(bucketStart) && observedAt.isBefore(bucketEnd);
            if (inBucket) {
                events.add(labelType(outcome).name());
            }
        }
        return distinct(events);
    }

    private boolean windowOverlaps(Instant bucketStart, Instant bucketEnd, MaintenanceWindowReadModel window) {
        return window.getStartAt().isBefore(bucketEnd) && window.getEndAt().isAfter(bucketStart);
    }

    private void assertPreviewFresh(UUID accountId, MlInternalDatasetRequest request, ValidationResult validation) {
        if (isBlank(request.previewHash()) && isBlank(request.previewId())) {
            return;
        }
        if (isBlank(request.previewHash())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "previewHash is required when previewId is supplied");
        }
        MlDatasetLabelReadinessDto labelReadiness = labelReadiness(
                accountId,
                normalizedModelFamily(request.modelFamily()),
                request.horizonDays(),
                request.from(),
                request.to(),
                validation);
        String currentHash = previewHash(accountId, request, validation, labelReadiness);
        if (!request.previewHash().equals(currentHash)) {
            Map<String, Object> dependency = new LinkedHashMap<>();
            dependency.put("type", "preview");
            dependency.put("id", isBlank(request.previewId()) ? previewId(request.previewHash()) : request.previewId());
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("code", "PREVIEW_STALE");
            details.put("submittedPreviewHash", request.previewHash());
            details.put("currentPreviewHash", currentHash);
            details.put("validationMessages", validation.messages());
            dependency.put("details", details);
            throw new MlLifecycleConflictException(
                    "PREVIEW_STALE",
                    "Dataset preview is stale; request a fresh preview before creating.",
                    dependency);
        }
    }

    private Map<String, Object> qualityProfileWithPreview(
            UUID accountId,
            MlInternalDatasetRequest request,
            ValidationResult validation) {
        Map<String, Object> qualityProfile = new LinkedHashMap<>(validation.qualityProfile());
        MlDatasetLabelReadinessDto labelReadiness = labelReadiness(
                accountId,
                normalizedModelFamily(request.modelFamily()),
                request.horizonDays(),
                request.from(),
                request.to(),
                validation);
        if (labelReadiness != null) {
            qualityProfile.put("labelCoverage", labelReadinessMap(labelReadiness));
        }
        if (!isBlank(request.previewHash()) || !isBlank(request.previewId())) {
            Map<String, Object> preview = new LinkedHashMap<>();
            preview.put("previewId", isBlank(request.previewId()) ? previewId(request.previewHash()) : request.previewId());
            preview.put("previewHash", request.previewHash());
            preview.put("currentPreviewHash", previewHash(accountId, request, validation, labelReadiness));
            preview.put("modelFamily", normalizedModelFamily(request.modelFamily()).name());
            preview.put("horizonDays", request.horizonDays());
            preview.put("metricSelectionMode", normalizedMetricSelectionMode(request.metricSelectionMode()).name());
            preview.put("expiresMatchedAtCreate", true);
            if (labelReadiness != null) {
                preview.put("labelReadiness", labelReadinessMap(labelReadiness));
            }
            qualityProfile.put("selectedPreview", preview);
        }
        return qualityProfile;
    }

    private String previewHash(
            UUID accountId,
            MlInternalDatasetRequest request,
            ValidationResult validation,
            MlDatasetLabelReadinessDto labelReadiness) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("algorithmVersion", PREVIEW_ALGORITHM_VERSION);
        payload.put("accountId", accountId.toString());
        payload.put("deviceIds", normalizedDeviceIds(request.deviceIds()).stream().map(UUID::toString).sorted().toList());
        payload.put("metrics", normalizedMetrics(request.metrics()).stream().sorted().toList());
        payload.put("modelFamily", normalizedModelFamily(request.modelFamily()).name());
        payload.put("horizonDays", request.horizonDays());
        payload.put("metricSelectionMode", normalizedMetricSelectionMode(request.metricSelectionMode()).name());
        payload.put("from", request.from().toString());
        payload.put("to", request.to().toString());
        payload.put("splitStrategy", normalizedSplitStrategy(request.splitStrategy()).name());
        payload.put("splitConfigJson", blankToDefault(request.splitConfigJson(), "{\"train\":0.7,\"validation\":0.15,\"test\":0.15}"));
        payload.put("featureConfigVersion", featureConfigVersion(request.featureConfigVersion()));
        payload.put("minSamplesPerMetric", minSamplesPerMetric(request.minSamplesPerMetric()));
        payload.put("maxSamplingGapSeconds", maxSamplingGapSeconds(request.maxSamplingGapSeconds()));
        Map<String, Object> validationPayload = new LinkedHashMap<>();
        validationPayload.put("acceptedRows", validation.acceptedRows());
        validationPayload.put("rejectedRows", validation.rejectedRows());
        validationPayload.put("warningCount", validation.warningCount());
        validationPayload.put("rejectedReasons", sortedLongMap(validation.rejectedReasons()));
        validationPayload.put("mappingStats", sortedLongMap(validation.mappingStats()));
        validationPayload.put("coverageByDeviceMetric", sortedLongMap(longMapFromQualityProfile(validation.qualityProfile(), "coverageByDeviceMetric")));
        validationPayload.put("maxSamplingGapSeconds", longQualityValue(validation.qualityProfile(), "maxSamplingGapSeconds"));
        validationPayload.put("duplicateTimestampRate", validation.qualityProfile().getOrDefault("duplicateTimestampRate", 0.0));
        validationPayload.put("nonFiniteImpossibleValueCount", validation.qualityProfile().getOrDefault("nonFiniteImpossibleValueCount", 0L));
        validationPayload.put("maintenanceOverlapCount", validation.qualityProfile().getOrDefault("maintenanceOverlapCount", 0L));
        payload.put("validation", validationPayload);
        payload.put("maintenanceWindowIdsByDevice", sortedUuidListMap(validation.maintenanceWindowIdsByDevice()));
        payload.put("labelReadiness", labelReadiness == null ? Map.of() : labelReadinessMap(labelReadiness));
        return sha256(writeJson(payload));
    }

    private double candidateScore(
            ValidationResult validation,
            MlDatasetLabelReadinessDto labelReadiness,
            int deviceCount,
            int metricCount,
            int minSamples,
            long maxGapSeconds) {
        double coverageScore = coverageRatio(validation, deviceCount, metricCount);
        long expectedRows = (long) Math.max(1, deviceCount) * Math.max(1, metricCount) * minSamples;
        double densityScore = Math.min(1.0, (double) validation.acceptedRows() / expectedRows);
        long gap = longQualityValue(validation.qualityProfile(), "maxSamplingGapSeconds");
        double gapScore = gap <= maxGapSeconds || gap == 0 ? 1.0 : Math.max(0.0, (double) maxGapSeconds / gap);
        double qualityScore = validation.rejectedRows() == 0 ? 1.0 : 0.25;
        double labelScore = labelReadiness == null
                ? 1.0
                : labelReadiness.positiveLabels() > 0
                        ? Math.min(1.0, labelReadiness.coverageRatio())
                        : 0.0;
        return Math.round((0.30 * coverageScore
                + 0.20 * densityScore
                + 0.15 * gapScore
                + 0.15 * qualityScore
                + 0.20 * labelScore) * 100.0) / 100.0;
    }

    private double coverageRatio(ValidationResult validation, int deviceCount, int metricCount) {
        if (deviceCount == 0 || metricCount == 0) {
            return 0.0;
        }
        Map<String, Long> coverageByDeviceMetric = longMapFromQualityProfile(validation.qualityProfile(), "coverageByDeviceMetric");
        long coveredPairs = coverageByDeviceMetric.values().stream().filter(value -> value > 0).count();
        return Math.min(1.0, (double) coveredPairs / ((long) deviceCount * metricCount));
    }

    private MlMetricSelectionMode normalizedMetricSelectionMode(MlMetricSelectionMode value) {
        return value == null ? MlMetricSelectionMode.COMMON : value;
    }

    private MlModelFamily normalizedModelFamily(MlModelFamily value) {
        return value == null ? MlModelFamily.ANOMALY_DETECTION : value;
    }

    private MlTrainingSplitStrategy normalizedSplitStrategy(MlTrainingSplitStrategy value) {
        return value == null ? MlTrainingSplitStrategy.TIME_BASED : value;
    }

    private List<String> normalizedMetrics(List<String> metrics) {
        if (metrics == null) {
            return List.of();
        }
        return metrics.stream()
                .filter(metric -> metric != null && !metric.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    private List<UUID> normalizedDeviceIds(List<UUID> deviceIds) {
        if (deviceIds == null) {
            return List.of();
        }
        return deviceIds.stream()
                .filter(id -> id != null)
                .distinct()
                .toList();
    }

    private int minSamplesPerMetric(Integer value) {
        return value == null ? DEFAULT_MIN_SAMPLES_PER_METRIC : value;
    }

    private long maxSamplingGapSeconds(Long value) {
        return value == null ? DEFAULT_MAX_SAMPLING_GAP_SECONDS : value;
    }

    private String featureConfigVersion(String value) {
        return blankToDefault(value, "feature-config-v1");
    }

    private int candidateLimit(Integer value) {
        if (value == null) {
            return 4;
        }
        return Math.max(1, Math.min(8, value));
    }

    private String previewId(String previewHash) {
        if (previewHash == null || previewHash.length() <= "sha256:".length()) {
            return "prev_unknown";
        }
        return "prev_" + previewHash.substring("sha256:".length(), Math.min(previewHash.length(), "sha256:".length() + 16));
    }

    private String candidateId(MlCandidateWindowKind kind) {
        return "candidate-" + kind.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private List<String> distinct(List<String> values) {
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private long duplicateTimestampCount(List<TelemetryChartResponse.Point> points) {
        Set<Instant> seen = new LinkedHashSet<>();
        long duplicates = 0;
        for (TelemetryChartResponse.Point point : points) {
            if (!seen.add(point.timestamp())) {
                duplicates++;
            }
        }
        return duplicates;
    }

    private boolean isMlExcluded(ExtractedTelemetryRow row, List<MaintenanceWindowReadModel> windows) {
        return windows.stream()
                .filter(MaintenanceWindowReadModel::isExcludeFromMl)
                .anyMatch(window -> !row.timestamp().isBefore(window.getStartAt()) && row.timestamp().isBefore(window.getEndAt()));
    }

    private MlLabelType labelType(MaintenanceOutcomeReadModel outcome) {
        return switch (outcome.getOutcomeType()) {
            case "CONFIRMED_FAILURE" -> MlLabelType.CONFIRMED_FAILURE;
            case "CORRECTIVE_MAINTENANCE" -> MlLabelType.CORRECTIVE_MAINTENANCE;
            case "PREVENTIVE_MAINTENANCE" -> MlLabelType.PREVENTIVE_MAINTENANCE;
            case "INSPECTION_NORMAL" -> MlLabelType.INSPECTION_NORMAL;
            case "FALSE_POSITIVE" -> MlLabelType.FALSE_POSITIVE;
            case "SENSOR_FAULT" -> MlLabelType.SENSOR_FAULT;
            case "UNRESOLVED_ANOMALY" -> MlLabelType.UNRESOLVED_ANOMALY;
            default -> MlLabelType.UNKNOWN;
        };
    }

    private boolean isPositive(MlLabelType label) {
        return label == MlLabelType.CONFIRMED_FAILURE || label == MlLabelType.CORRECTIVE_MAINTENANCE;
    }

    private long longQualityValue(Map<String, Object> qualityProfile, String key) {
        Object value = qualityProfile.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        return 0;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Long> longMapFromQualityProfile(Map<String, Object> qualityProfile, String key) {
        Object value = qualityProfile.get(key);
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Long> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() instanceof String stringKey && entry.getValue() instanceof Number numberValue) {
                result.put(stringKey, numberValue.longValue());
            }
        }
        return result;
    }

    private Map<String, Long> sortedLongMap(Map<String, Long> values) {
        Map<String, Long> sorted = new LinkedHashMap<>();
        values.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
        return sorted;
    }

    private Map<String, List<String>> sortedUuidListMap(Map<UUID, List<UUID>> values) {
        Map<String, List<String>> sorted = new LinkedHashMap<>();
        values.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> sorted.put(
                        entry.getKey().toString(),
                        entry.getValue().stream().map(UUID::toString).sorted().toList()));
        return sorted;
    }

    private Map<String, Object> labelReadinessMap(MlDatasetLabelReadinessDto labelReadiness) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("horizonDays", labelReadiness.horizonDays());
        value.put("positiveLabels", labelReadiness.positiveLabels());
        value.put("normalLabels", labelReadiness.normalLabels());
        value.put("unresolvedLabels", labelReadiness.unresolvedLabels());
        value.put("unobservableRows", labelReadiness.unobservableRows());
        value.put("maintenanceExcludedLabels", labelReadiness.maintenanceExcludedLabels());
        value.put("coverageRatio", labelReadiness.coverageRatio());
        value.put("status", labelReadiness.status().name());
        return value;
    }

    private Instant maxInstant(Instant left, Instant right) {
        return left.isAfter(right) ? left : right;
    }

    private Instant minInstant(Instant left, Instant right) {
        return left.isBefore(right) ? left : right;
    }

    private Map<String, Object> lineageFilter(MlInternalDatasetRequest request, ValidationResult validation) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("from", request.from().toString());
        value.put("to", request.to().toString());
        value.put("deviceType", request.deviceType());
        value.put("deviceMetadata", request.deviceMetadata() == null ? Map.of() : request.deviceMetadata());
        value.put("maintenanceWindowIdsByDevice", validation.maintenanceWindowIdsByDevice());
        value.put("minSamplesPerMetric", request.minSamplesPerMetric() == null ? DEFAULT_MIN_SAMPLES_PER_METRIC : request.minSamplesPerMetric());
        value.put("maxSamplingGapSeconds", request.maxSamplingGapSeconds() == null
                ? DEFAULT_MAX_SAMPLING_GAP_SECONDS
                : request.maxSamplingGapSeconds());
        value.put("previewId", request.previewId());
        value.put("previewHash", request.previewHash());
        value.put("modelFamily", normalizedModelFamily(request.modelFamily()).name());
        value.put("horizonDays", request.horizonDays());
        value.put("metricSelectionMode", normalizedMetricSelectionMode(request.metricSelectionMode()).name());
        value.put("validationMessages", validation.messages());
        value.put("materializedRowCount", validation.extractedRows().size());
        value.put("materializedRowPreview", validation.extractedRows().stream()
                .limit(10)
                .map(row -> Map.of(
                        "deviceId", row.deviceId().toString(),
                        "metricName", row.metricName(),
                        "canonicalMetricName", row.canonicalMetricName(),
                        "rawUnit", row.rawUnit() == null ? "" : row.rawUnit(),
                        "canonicalUnit", row.canonicalUnit() == null ? "" : row.canonicalUnit(),
                        "timestamp", row.timestamp().toString()))
                .toList());
        return value;
    }

    private List<MlCanonicalMetricRow> canonicalRows(
            UUID accountId,
            UUID datasetVersionId,
            MlInternalDatasetRequest request,
            List<ExtractedTelemetryRow> rows,
            Instant now) {
        return rows.stream()
                .map(row -> {
                    MlCanonicalMetricRow canonical = new MlCanonicalMetricRow();
                    canonical.setId(UUID.randomUUID());
                    canonical.setAccountId(accountId);
                    canonical.setDatasetVersionId(datasetVersionId);
                    canonical.setSourceType(MlDatasetSourceType.INTERNAL_TELEMETRY);
                    canonical.setDeviceId(row.deviceId());
                    canonical.setExternalDeviceKey(null);
                    canonical.setVirtualDeviceKey("internal:" + accountId + ":" + row.deviceId());
                    canonical.setDeviceType(request.deviceType());
                    canonical.setSourceId("telemetry-service");
                    canonical.setSensorId(row.metricName());
                    canonical.setTimestamp(row.timestamp());
                    canonical.setMetricName(row.metricName());
                    canonical.setCanonicalMetricName(row.canonicalMetricName());
                    canonical.setNumericValue(row.numericValue());
                    canonical.setRawUnit(row.rawUnit());
                    canonical.setCanonicalUnit(row.canonicalUnit());
                    canonical.setQualityFlags("[]");
                    canonical.setMetadataJson(writeJson(request.deviceMetadata() == null ? Map.of() : request.deviceMetadata()));
                    canonical.setSourceLineage(writeJson(Map.of(
                            "source", "internal_telemetry",
                            "deviceId", row.deviceId().toString(),
                            "metricName", row.metricName(),
                            "timestamp", row.timestamp().toString())));
                    canonical.setCreatedAt(now);
                    return canonical;
                })
                .toList();
    }

    private String immutableRowReference(UUID datasetVersionId) {
        return "ml_canonical_metric_rows:dataset_version_id=" + datasetVersionId;
    }

    private String dataHash(List<ExtractedTelemetryRow> rows) {
        StringBuilder builder = new StringBuilder();
        for (ExtractedTelemetryRow row : rows) {
            builder.append(row.deviceId()).append('|')
                    .append(row.metricName()).append('|')
                    .append(row.canonicalMetricName()).append('|')
                    .append(row.rawUnit() == null ? "" : row.rawUnit()).append('|')
                    .append(row.canonicalUnit() == null ? "" : row.canonicalUnit()).append('|')
                    .append(row.timestamp()).append('|')
                    .append(Double.toString(row.numericValue()))
                    .append('\n');
        }
        return sha256(builder.toString());
    }

    private NormalizedMetric normalizeMetric(String metricName) {
        String metric = metricName == null ? "" : metricName.trim();
        return switch (metric) {
            case "activePowerW", "active_power_w", "active_power" -> new NormalizedMetric(metric, "active_power", "W");
            case "voltage", "voltageV", "voltage_v" -> new NormalizedMetric(metric, "voltage", "V");
            case "current", "currentA", "current_a" -> new NormalizedMetric(metric, "current", "A");
            case "energyGrowth", "energy_growth", "energyGrowthWh", "energy_growth_wh" -> new NormalizedMetric(metric, "energy_growth", "Wh");
            default -> new NormalizedMetric(metric, toSnakeCase(metric), null);
        };
    }

    private String toSnakeCase(String value) {
        StringBuilder builder = new StringBuilder();
        char previous = 0;
        for (char current : value.toCharArray()) {
            if (Character.isUpperCase(current) && builder.length() > 0 && previous != '_' && previous != '-') {
                builder.append('_');
            }
            if (current == '-' || current == ' ' || current == '.') {
                builder.append('_');
            } else {
                builder.append(Character.toLowerCase(current));
            }
            previous = current;
        }
        return builder.toString().replaceAll("_+", "_").toLowerCase(Locale.ROOT);
    }

    private long maxGapSeconds(List<TelemetryChartResponse.Point> points) {
        long max = 0;
        for (int index = 1; index < points.size(); index++) {
            long gap = Math.abs(Duration.between(points.get(index - 1).timestamp(), points.get(index).timestamp()).toSeconds());
            max = Math.max(max, gap);
        }
        return max;
    }

    private void increment(Map<String, Long> values, String key) {
        increment(values, key, 1);
    }

    private void increment(Map<String, Long> values, String key, long amount) {
        values.merge(key, amount, Long::sum);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not serialize ML dataset metadata");
        }
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder("sha256:");
            for (byte b : digest) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private MlDatasetSummaryDto summary(MlDataset dataset, MlDatasetVersion version) {
        return new MlDatasetSummaryDto(
                dataset.getId(),
                version.getId(),
                dataset.getName(),
                dataset.getDescription(),
                version.getSourceType(),
                version.getVersion(),
                version.getStatus(),
                version.getSplitStrategy(),
                version.getFeatureConfigVersion(),
                version.getSourceWindowStart(),
                version.getSourceWindowEnd(),
                version.getCreatedAt(),
                version.getReadyAt(),
                version.getFailureReason(),
                version.getArtifactUri(),
                version.getArtifactHash(),
                version.getImmutableRowReference(),
                version.getDataHash());
    }

    private MlValidationReportDto validationReport(MlValidationReport report) {
        return new MlValidationReportDto(
                report.getId(),
                report.getDatasetVersionId(),
                report.getImportedFileId(),
                report.getAcceptedRows(),
                report.getRejectedRows(),
                report.getWarningCount(),
                readLongMap(report.getRejectedReasonsJson()),
                readLongMap(report.getMappingStatsJson()),
                readObjectMap(report.getQualityProfileJson()),
                report.getSummary(),
                report.getCreatedAt());
    }

    private MlLineageDto lineage(MlDatasetLineage lineage) {
        return new MlLineageDto(
                lineage.getId(),
                lineage.getDatasetVersionId(),
                lineage.getSourceType(),
                lineage.getSourceReference(),
                lineage.getFilterJson(),
                lineage.getSelectedDevicesJson(),
                lineage.getSelectedMetricsJson(),
                lineage.getSourceWindowStart(),
                lineage.getSourceWindowEnd(),
                lineage.getContentHash());
    }

    private Map<String, Long> readLongMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(
                    json,
                    objectMapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Long.class));
        } catch (JsonProcessingException exception) {
            return Map.of();
        }
    }

    private Map<String, Object> readObjectMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(
                    json,
                    objectMapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class));
        } catch (JsonProcessingException exception) {
            return Map.of();
        }
    }

    private record MetricMetadata(
            String metricName,
            String displayName,
            String canonicalMetricName,
            String unit) {
    }

    private record PreviewDecision(
            MlWorkbenchStatus status,
            List<String> blockingReasons,
            List<String> warningReasons,
            List<MlDatasetQualityGateDto> qualityGates) {
    }

    private record CandidateDraft(
            MlCandidateWindowKind kind,
            Instant from,
            Instant to,
            double score,
            MlWorkbenchStatus status,
            long sampleCount,
            double coverageRatio,
            long maxSamplingGapSeconds,
            long positiveLabels,
            long normalLabels,
            long unobservableLabels,
            long maintenanceExcludedLabels,
            List<String> blockingReasons,
            List<String> warningReasons,
            String explanation) {
    }

    private record TimeWindow(
            Instant from,
            Instant to,
            String explanation) {
    }

    private record ValidationResult(
            boolean ready,
            long acceptedRows,
            long rejectedRows,
            long warningCount,
            List<String> messages,
            Map<String, Long> rejectedReasons,
            Map<String, Long> mappingStats,
            Map<UUID, List<UUID>> maintenanceWindowIdsByDevice,
            Map<String, Object> qualityProfile,
            List<ExtractedTelemetryRow> extractedRows) {
    }

    private record ExtractedTelemetryRow(
            UUID deviceId,
            String metricName,
            String canonicalMetricName,
            String rawUnit,
            String canonicalUnit,
            Instant timestamp,
            double numericValue) {
    }

    private record NormalizedMetric(
            String rawMetricName,
            String canonicalMetricName,
            String canonicalUnit) {
        String rawUnit() {
            return canonicalUnit;
        }
    }
}
