package com.voltcanary.analytics.service;

import com.voltcanary.analytics.domain.MlModelFamily;
import com.voltcanary.analytics.dto.MlCandidateWindowKind;
import com.voltcanary.analytics.dto.MlDatasetCandidateWindowDto;
import com.voltcanary.analytics.dto.MlDatasetLabelReadinessDto;
import com.voltcanary.analytics.dto.MlDatasetMetricCoverageDto;
import com.voltcanary.analytics.dto.MlDatasetPreviewCreateDefaultsDto;
import com.voltcanary.analytics.dto.MlDatasetWindowDto;
import com.voltcanary.analytics.dto.MlDatasetWorkbenchPreviewRequest;
import com.voltcanary.analytics.dto.MlDatasetWorkbenchPreviewResponse;
import com.voltcanary.analytics.dto.MlInternalDatasetRequest;
import com.voltcanary.analytics.dto.MlMetricSelectionMode;
import com.voltcanary.analytics.service.MlPreviewDecisions.PreviewDecision;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MlDatasetPreviewService {

    private final MlDatasetCandidateWindows candidateWindows;
    private final MlPreviewDecisions decisions;
    private final MlDatasetMetricCoverage metricCoverage;
    private final MlDatasetTimeline timeline;
    private final MlDatasetValidationService validationService;
    private final MlDatasetLabelReadinessService labelReadinessService;
    private final MlDatasetSupportService support;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public MlDatasetWorkbenchPreviewResponse previewInternalTelemetryDataset(
        UUID accountId,
        MlDatasetWorkbenchPreviewRequest request
    ) {
        validationService.validatePreviewRequest(request);
        MlMetricSelectionMode metricSelectionMode = support.normalizedMetricSelectionMode(
            request.metricSelectionMode()
        );
        MlModelFamily modelFamily = support.normalizedModelFamily(request.modelFamily());
        List<String> selectedMetrics = support.normalizedMetrics(request.selectedMetrics());
        List<UUID> deviceIds = support.normalizedDeviceIds(request.deviceIds());
        int minSamples = support.minSamplesPerMetric(request.minSamplesPerMetric());
        long maxGapSeconds = support.maxSamplingGapSeconds(request.maxSamplingGapSeconds());

        List<MlDatasetMetricCoverageDto> availableMetrics = metricCoverage.availableMetrics(
            deviceIds,
            request.from(),
            request.to(),
            metricSelectionMode,
            minSamples,
            maxGapSeconds
        );

        MlInternalDatasetRequest validationRequest = internalRequestFromPreview(
            request,
            selectedMetrics
        );
        MlDatasetValidationResult validation = validationService.validateTelemetry(
            validationRequest
        );
        MlDatasetLabelReadinessDto labelReadiness = labelReadinessService.labelReadiness(
            accountId,
            modelFamily,
            request.horizonDays(),
            request.from(),
            request.to(),
            validation
        );
        PreviewDecision decision = decisions.previewDecision(
            validationRequest,
            modelFamily,
            request.horizonDays(),
            metricSelectionMode,
            availableMetrics,
            validation,
            labelReadiness
        );

        String previewHash = previewHash(accountId, validationRequest, validation, labelReadiness);
        List<MlDatasetCandidateWindowDto> candidates = candidateWindows.candidateWindows(
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
            support.candidateLimit(request.candidateLimit())
        );
        String recommendedCandidateId = candidates
            .stream()
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
            new MlDatasetWindowDto(
                request.from(),
                request.to(),
                MlCandidateWindowKind.USER_SELECTED
            ),
            availableMetrics,
            candidates,
            timeline.timeline(accountId, request, validation),
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
                metricSelectionMode
            )
        );
    }

    public MlInternalDatasetRequest internalRequestFromPreview(
        MlDatasetWorkbenchPreviewRequest request,
        List<String> selectedMetrics
    ) {
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
            support.normalizedMetricSelectionMode(request.metricSelectionMode())
        );
    }

    public String previewHash(
        UUID accountId,
        MlInternalDatasetRequest request,
        MlDatasetValidationResult validation,
        MlDatasetLabelReadinessDto labelReadiness
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("algorithmVersion", MlDatasetSupportService.PREVIEW_ALGORITHM_VERSION);
        payload.put("accountId", accountId.toString());
        payload.put(
            "deviceIds",
            support
                .normalizedDeviceIds(request.deviceIds())
                .stream()
                .map(UUID::toString)
                .sorted()
                .toList()
        );
        payload.put(
            "metrics",
            support.normalizedMetrics(request.metrics()).stream().sorted().toList()
        );
        payload.put("modelFamily", support.normalizedModelFamily(request.modelFamily()).name());
        payload.put("horizonDays", request.horizonDays());
        payload.put(
            "metricSelectionMode",
            support.normalizedMetricSelectionMode(request.metricSelectionMode()).name()
        );
        payload.put("from", request.from().toString());
        payload.put("to", request.to().toString());
        payload.put(
            "splitStrategy",
            support.normalizedSplitStrategy(request.splitStrategy()).name()
        );
        payload.put(
            "splitConfigJson",
            support.blankToDefault(
                request.splitConfigJson(),
                MlDatasetSupportService.DEFAULT_SPLIT_CONFIG
            )
        );
        payload.put(
            "featureConfigVersion",
            support.featureConfigVersion(request.featureConfigVersion())
        );
        payload.put(
            "minSamplesPerMetric",
            support.minSamplesPerMetric(request.minSamplesPerMetric())
        );
        payload.put(
            "maxSamplingGapSeconds",
            support.maxSamplingGapSeconds(request.maxSamplingGapSeconds())
        );
        Map<String, Object> validationPayload = new LinkedHashMap<>();
        validationPayload.put("acceptedRows", validation.acceptedRows());
        validationPayload.put("rejectedRows", validation.rejectedRows());
        validationPayload.put("warningCount", validation.warningCount());
        validationPayload.put(
            "rejectedReasons",
            support.sortedLongMap(validation.rejectedReasons())
        );
        validationPayload.put("mappingStats", support.sortedLongMap(validation.mappingStats()));
        validationPayload.put(
            "coverageByDeviceMetric",
            support.sortedLongMap(
                support.longMapFromQualityProfile(
                    validation.qualityProfile(),
                    "coverageByDeviceMetric"
                )
            )
        );
        validationPayload.put(
            "maxSamplingGapSeconds",
            support.longQualityValue(validation.qualityProfile(), "maxSamplingGapSeconds")
        );
        validationPayload.put(
            "duplicateTimestampRate",
            validation.qualityProfile().getOrDefault("duplicateTimestampRate", 0.0)
        );
        validationPayload.put(
            "nonFiniteImpossibleValueCount",
            validation.qualityProfile().getOrDefault("nonFiniteImpossibleValueCount", 0L)
        );
        validationPayload.put(
            "maintenanceOverlapCount",
            validation.qualityProfile().getOrDefault("maintenanceOverlapCount", 0L)
        );
        payload.put("validation", validationPayload);
        payload.put(
            "maintenanceWindowIdsByDevice",
            support.sortedUuidListMap(validation.maintenanceWindowIdsByDevice())
        );
        payload.put(
            "labelReadiness",
            labelReadiness == null ? Map.of() : support.labelReadinessMap(labelReadiness)
        );
        return support.sha256(support.writeJson(payload));
    }
}
