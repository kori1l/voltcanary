package com.voltcanary.analytics.service;

import com.voltcanary.analytics.domain.MaintenanceOutcomeReadModel;
import com.voltcanary.analytics.domain.MaintenanceWindowReadModel;
import com.voltcanary.analytics.domain.MlModelFamily;
import com.voltcanary.analytics.dto.MlCandidateWindowKind;
import com.voltcanary.analytics.dto.MlDatasetCandidateWindowDto;
import com.voltcanary.analytics.dto.MlDatasetLabelReadinessDto;
import com.voltcanary.analytics.dto.MlDatasetMetricCoverageDto;
import com.voltcanary.analytics.dto.MlDatasetPreviewCreateDefaultsDto;
import com.voltcanary.analytics.dto.MlDatasetQualityGateDto;
import com.voltcanary.analytics.dto.MlDatasetTimelineBucketDto;
import com.voltcanary.analytics.dto.MlDatasetWindowDto;
import com.voltcanary.analytics.dto.MlDatasetWorkbenchPreviewRequest;
import com.voltcanary.analytics.dto.MlDatasetWorkbenchPreviewResponse;
import com.voltcanary.analytics.dto.MlInternalDatasetRequest;
import com.voltcanary.analytics.dto.MlMetricSelectionMode;
import com.voltcanary.analytics.dto.MlWorkbenchGateStatus;
import com.voltcanary.analytics.dto.MlWorkbenchStatus;
import com.voltcanary.analytics.repository.MaintenanceOutcomeReadModelRepository;
import com.voltcanary.analytics.repository.MaintenanceWindowReadModelRepository;
import com.voltcanary.analytics.telemetry.TelemetryChartResponse;
import com.voltcanary.analytics.telemetry.TelemetryClient;
import com.voltcanary.analytics.telemetry.TelemetryMetricResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MlDatasetPreviewService {

    private final MaintenanceOutcomeReadModelRepository maintenanceOutcomeRepository;
    private final MaintenanceWindowReadModelRepository maintenanceWindowRepository;
    private final TelemetryClient telemetryClient;
    private final MlDatasetValidationService validationService;
    private final MlDatasetLabelReadinessService labelReadinessService;
    private final MlDatasetSupportService support;

    public MlDatasetPreviewService(
            MaintenanceOutcomeReadModelRepository maintenanceOutcomeRepository,
            MaintenanceWindowReadModelRepository maintenanceWindowRepository,
            TelemetryClient telemetryClient,
            MlDatasetValidationService validationService,
            MlDatasetLabelReadinessService labelReadinessService,
            MlDatasetSupportService support) {
        this.maintenanceOutcomeRepository = maintenanceOutcomeRepository;
        this.maintenanceWindowRepository = maintenanceWindowRepository;
        this.telemetryClient = telemetryClient;
        this.validationService = validationService;
        this.labelReadinessService = labelReadinessService;
        this.support = support;
    }

    @Transactional(readOnly = true)
    public MlDatasetWorkbenchPreviewResponse previewInternalTelemetryDataset(
            UUID accountId,
            MlDatasetWorkbenchPreviewRequest request) {
        validationService.validatePreviewRequest(request);
        MlMetricSelectionMode metricSelectionMode = support.normalizedMetricSelectionMode(request.metricSelectionMode());
        MlModelFamily modelFamily = support.normalizedModelFamily(request.modelFamily());
        List<String> selectedMetrics = support.normalizedMetrics(request.selectedMetrics());
        List<UUID> deviceIds = support.normalizedDeviceIds(request.deviceIds());
        int minSamples = support.minSamplesPerMetric(request.minSamplesPerMetric());
        long maxGapSeconds = support.maxSamplingGapSeconds(request.maxSamplingGapSeconds());

        List<MlDatasetMetricCoverageDto> availableMetrics = availableMetrics(
                deviceIds,
                request.from(),
                request.to(),
                metricSelectionMode,
                minSamples,
                maxGapSeconds);

        MlInternalDatasetRequest validationRequest = internalRequestFromPreview(request, selectedMetrics);
        MlDatasetValidationResult validation = validationService.validateTelemetry(validationRequest);
        MlDatasetLabelReadinessDto labelReadiness = labelReadinessService.labelReadiness(
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
                support.candidateLimit(request.candidateLimit()));
        String recommendedCandidateId = candidates.stream()
                .filter(MlDatasetCandidateWindowDto::recommended)
                .map(MlDatasetCandidateWindowDto::id)
                .findFirst()
                .orElse(null);

        return new MlDatasetWorkbenchPreviewResponse(
                support.previewId(previewHash),
                previewHash,
                Instant.now().plus(MlDatasetSupportService.PREVIEW_TTL),
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
                        support.normalizedSplitStrategy(request.splitStrategy()),
                        support.featureConfigVersion(request.featureConfigVersion()),
                        minSamples,
                        maxGapSeconds,
                        modelFamily,
                        request.horizonDays(),
                        metricSelectionMode));
    }

    public MlInternalDatasetRequest internalRequestFromPreview(
            MlDatasetWorkbenchPreviewRequest request,
            List<String> selectedMetrics) {
        return new MlInternalDatasetRequest(
                "preview",
                null,
                support.normalizedDeviceIds(request.deviceIds()),
                request.deviceType(),
                request.deviceMetadata(),
                selectedMetrics,
                request.from(),
                request.to(),
                support.normalizedSplitStrategy(request.splitStrategy()),
                request.splitConfigJson(),
                support.featureConfigVersion(request.featureConfigVersion()),
                support.minSamplesPerMetric(request.minSamplesPerMetric()),
                support.maxSamplingGapSeconds(request.maxSamplingGapSeconds()),
                null,
                null,
                support.normalizedModelFamily(request.modelFamily()),
                request.horizonDays(),
                support.normalizedMetricSelectionMode(request.metricSelectionMode()));
    }

    public String previewHash(
            UUID accountId,
            MlInternalDatasetRequest request,
            MlDatasetValidationResult validation,
            MlDatasetLabelReadinessDto labelReadiness) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("algorithmVersion", MlDatasetSupportService.PREVIEW_ALGORITHM_VERSION);
        payload.put("accountId", accountId.toString());
        payload.put("deviceIds", support.normalizedDeviceIds(request.deviceIds()).stream().map(UUID::toString).sorted().toList());
        payload.put("metrics", support.normalizedMetrics(request.metrics()).stream().sorted().toList());
        payload.put("modelFamily", support.normalizedModelFamily(request.modelFamily()).name());
        payload.put("horizonDays", request.horizonDays());
        payload.put("metricSelectionMode", support.normalizedMetricSelectionMode(request.metricSelectionMode()).name());
        payload.put("from", request.from().toString());
        payload.put("to", request.to().toString());
        payload.put("splitStrategy", support.normalizedSplitStrategy(request.splitStrategy()).name());
        payload.put("splitConfigJson", support.blankToDefault(request.splitConfigJson(), MlDatasetSupportService.DEFAULT_SPLIT_CONFIG));
        payload.put("featureConfigVersion", support.featureConfigVersion(request.featureConfigVersion()));
        payload.put("minSamplesPerMetric", support.minSamplesPerMetric(request.minSamplesPerMetric()));
        payload.put("maxSamplingGapSeconds", support.maxSamplingGapSeconds(request.maxSamplingGapSeconds()));
        Map<String, Object> validationPayload = new LinkedHashMap<>();
        validationPayload.put("acceptedRows", validation.acceptedRows());
        validationPayload.put("rejectedRows", validation.rejectedRows());
        validationPayload.put("warningCount", validation.warningCount());
        validationPayload.put("rejectedReasons", support.sortedLongMap(validation.rejectedReasons()));
        validationPayload.put("mappingStats", support.sortedLongMap(validation.mappingStats()));
        validationPayload.put("coverageByDeviceMetric", support.sortedLongMap(support.longMapFromQualityProfile(validation.qualityProfile(), "coverageByDeviceMetric")));
        validationPayload.put("maxSamplingGapSeconds", support.longQualityValue(validation.qualityProfile(), "maxSamplingGapSeconds"));
        validationPayload.put("duplicateTimestampRate", validation.qualityProfile().getOrDefault("duplicateTimestampRate", 0.0));
        validationPayload.put("nonFiniteImpossibleValueCount", validation.qualityProfile().getOrDefault("nonFiniteImpossibleValueCount", 0L));
        validationPayload.put("maintenanceOverlapCount", validation.qualityProfile().getOrDefault("maintenanceOverlapCount", 0L));
        payload.put("validation", validationPayload);
        payload.put("maintenanceWindowIdsByDevice", support.sortedUuidListMap(validation.maintenanceWindowIdsByDevice()));
        payload.put("labelReadiness", labelReadiness == null ? Map.of() : support.labelReadinessMap(labelReadiness));
        return support.sha256(support.writeJson(payload));
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
                MlDatasetNormalizedMetric normalized = support.normalizeMetric(metric.name());
                metrics.put(metric.name(), new MetricMetadata(
                        metric.name(),
                        support.blankToDefault(metric.displayName(), metric.name()),
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
                    MlDatasetNormalizedMetric normalized = support.normalizeMetric(metricName);
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
            maxGapObserved = Math.max(maxGapObserved, support.maxGapSeconds(points));
            duplicateTimestamps += support.duplicateTimestampCount(points);
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

    private PreviewDecision previewDecision(
            MlInternalDatasetRequest request,
            MlModelFamily modelFamily,
            Integer horizonDays,
            MlMetricSelectionMode metricSelectionMode,
            List<MlDatasetMetricCoverageDto> availableMetrics,
            MlDatasetValidationResult validation,
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
                (long) support.minSamplesPerMetric(request.minSamplesPerMetric()) * Math.max(1, request.metrics().size()),
                true,
                validation.ready() ? "Selected metrics have enough samples." : "Selected metrics do not meet minimum sample gates."));
        long observedGap = support.longQualityValue(validation.qualityProfile(), "maxSamplingGapSeconds");
        qualityGates.add(new MlDatasetQualityGateDto(
                "maxSamplingGapSeconds",
                "Maximum sampling gap",
                observedGap > support.maxSamplingGapSeconds(request.maxSamplingGapSeconds()) ? MlWorkbenchGateStatus.WARN : MlWorkbenchGateStatus.PASS,
                observedGap,
                support.maxSamplingGapSeconds(request.maxSamplingGapSeconds()),
                false,
                observedGap > support.maxSamplingGapSeconds(request.maxSamplingGapSeconds())
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

        List<String> distinctBlocks = support.distinct(blockingReasons);
        List<String> distinctWarnings = support.distinct(warningReasons);
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
            MlDatasetValidationResult selectedValidation,
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
                        support.candidateId(draft.kind()),
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
            MlDatasetValidationResult existingValidation,
            MlDatasetLabelReadinessDto existingLabelReadiness,
            PreviewDecision existingDecision,
            String explanation) {
        MlInternalDatasetRequest candidateRequest = new MlInternalDatasetRequest(
                "preview",
                null,
                support.normalizedDeviceIds(request.deviceIds()),
                request.deviceType(),
                request.deviceMetadata(),
                selectedMetrics,
                from,
                to,
                support.normalizedSplitStrategy(request.splitStrategy()),
                request.splitConfigJson(),
                support.featureConfigVersion(request.featureConfigVersion()),
                minSamples,
                maxGapSeconds,
                null,
                null,
                modelFamily,
                request.horizonDays(),
                metricSelectionMode);
        MlDatasetValidationResult validation = existingValidation == null ? validationService.validateTelemetry(candidateRequest) : existingValidation;
        MlDatasetLabelReadinessDto labelReadiness = existingLabelReadiness == null
                ? labelReadinessService.labelReadiness(accountId, modelFamily, request.horizonDays(), from, to, validation)
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
                support.longQualityValue(validation.qualityProfile(), "maxSamplingGapSeconds"),
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
                .filter(outcome -> support.isPositive(support.labelType(outcome)))
                .findFirst()
                .orElse(null);
        if (positive == null || selectedMetrics.isEmpty()) {
            return new TimeWindow(request.from(), request.to(), "No failure-rich outcome was found in the searched range.");
        }
        Instant from = support.maxInstant(request.from(), positive.getObservedAt().minus(Duration.ofDays(horizonDays)));
        Instant to = support.minInstant(request.to(), positive.getObservedAt().plusSeconds(1));
        if (!to.isAfter(from)) {
            return new TimeWindow(request.from(), request.to(), "Failure-rich outcome exists, but it does not produce a valid candidate range.");
        }
        return new TimeWindow(from, to, "Window includes confirmed failure or corrective maintenance evidence.");
    }

    private Instant recentWindowStart(Instant from, Instant to) {
        Duration range = Duration.between(from, to);
        Duration recent = range.compareTo(Duration.ofDays(7)) > 0 ? Duration.ofDays(7) : range.dividedBy(2);
        return support.maxInstant(from, to.minus(recent));
    }

    private List<MlDatasetTimelineBucketDto> timeline(
            UUID accountId,
            MlDatasetWorkbenchPreviewRequest request,
            MlDatasetValidationResult validation) {
        Instant from = request.from();
        Instant to = request.to();
        if (!to.isAfter(from)) {
            return List.of();
        }
        List<UUID> selectedDeviceIds = support.normalizedDeviceIds(request.deviceIds());
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
                events.add(support.labelType(outcome).name());
            }
        }
        return support.distinct(events);
    }

    private boolean windowOverlaps(Instant bucketStart, Instant bucketEnd, MaintenanceWindowReadModel window) {
        return window.getStartAt().isBefore(bucketEnd) && window.getEndAt().isAfter(bucketStart);
    }

    private double candidateScore(
            MlDatasetValidationResult validation,
            MlDatasetLabelReadinessDto labelReadiness,
            int deviceCount,
            int metricCount,
            int minSamples,
            long maxGapSeconds) {
        double coverageScore = coverageRatio(validation, deviceCount, metricCount);
        long expectedRows = (long) Math.max(1, deviceCount) * Math.max(1, metricCount) * minSamples;
        double densityScore = Math.min(1.0, (double) validation.acceptedRows() / expectedRows);
        long gap = support.longQualityValue(validation.qualityProfile(), "maxSamplingGapSeconds");
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

    private double coverageRatio(MlDatasetValidationResult validation, int deviceCount, int metricCount) {
        if (deviceCount == 0 || metricCount == 0) {
            return 0.0;
        }
        Map<String, Long> coverageByDeviceMetric = support.longMapFromQualityProfile(validation.qualityProfile(), "coverageByDeviceMetric");
        long coveredPairs = coverageByDeviceMetric.values().stream().filter(value -> value > 0).count();
        return Math.min(1.0, (double) coveredPairs / ((long) deviceCount * metricCount));
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
}
