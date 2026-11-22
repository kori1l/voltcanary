package com.voltcanary.analytics.service;

import com.voltcanary.analytics.domain.MaintenanceOutcomeReadModel;
import com.voltcanary.analytics.domain.MlModelFamily;
import com.voltcanary.analytics.dto.MlCandidateWindowKind;
import com.voltcanary.analytics.dto.MlDatasetCandidateWindowDto;
import com.voltcanary.analytics.dto.MlDatasetLabelReadinessDto;
import com.voltcanary.analytics.dto.MlDatasetMetricCoverageDto;
import com.voltcanary.analytics.dto.MlDatasetWorkbenchPreviewRequest;
import com.voltcanary.analytics.dto.MlInternalDatasetRequest;
import com.voltcanary.analytics.dto.MlMetricSelectionMode;
import com.voltcanary.analytics.dto.MlWorkbenchStatus;
import com.voltcanary.analytics.repository.MaintenanceOutcomeReadModelRepository;
import com.voltcanary.analytics.service.MlPreviewDecisions.PreviewDecision;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MlDatasetCandidateWindows {

    private final MaintenanceOutcomeReadModelRepository maintenanceOutcomeRepository;
    private final MlDatasetValidationService validationService;
    private final MlDatasetLabelReadinessService labelReadinessService;
    private final MlDatasetSupportService support;
    private final MlPreviewDecisions decisions;

    public List<MlDatasetCandidateWindowDto> candidateWindows(
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
        int candidateLimit
    ) {
        List<CandidateDraft> drafts = new ArrayList<>();
        drafts.add(
            candidateDraft(
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
                "User selected window."
            )
        );
        if (!selectedMetrics.isEmpty() && drafts.size() < candidateLimit) {
            drafts.add(
                candidateDraft(
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
                    "Highest coverage in the searched range."
                )
            );
            Instant recentFrom = recentWindowStart(request.from(), request.to());
            if (recentFrom.isBefore(request.to()) && drafts.size() < candidateLimit) {
                drafts.add(
                    candidateDraft(
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
                        "Most recent stable telemetry window."
                    )
                );
            }
            if (modelFamily == MlModelFamily.FAILURE_PREDICTION && drafts.size() < candidateLimit) {
                TimeWindow failureWindow = failureRichWindow(accountId, request, selectedMetrics);
                drafts.add(
                    candidateDraft(
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
                        failureWindow.explanation()
                    )
                );
            }
        }
        double bestScore = drafts
            .stream()
            .filter(draft -> draft.status() != MlWorkbenchStatus.BLOCKED)
            .mapToDouble(CandidateDraft::score)
            .max()
            .orElse(drafts.stream().mapToDouble(CandidateDraft::score).max().orElse(0.0));
        return drafts
            .stream()
            .limit(candidateLimit)
            .map(draft ->
                new MlDatasetCandidateWindowDto(
                    support.candidateId(draft.kind()),
                    draft.kind(),
                    draft.from(),
                    draft.to(),
                    draft.score(),
                    draft.status(),
                    Double.compare(draft.score(), bestScore) == 0 &&
                        draft.status() != MlWorkbenchStatus.BLOCKED,
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
                    draft.explanation()
                )
            )
            .toList();
    }

    public CandidateDraft candidateDraft(
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
        String explanation
    ) {
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
            metricSelectionMode
        );
        MlDatasetValidationResult validation =
            existingValidation == null
                ? validationService.validateTelemetry(candidateRequest)
                : existingValidation;
        MlDatasetLabelReadinessDto labelReadiness =
            existingLabelReadiness == null
                ? labelReadinessService.labelReadiness(
                      accountId,
                      modelFamily,
                      request.horizonDays(),
                      from,
                      to,
                      validation
                  )
                : existingLabelReadiness;
        PreviewDecision decision =
            existingDecision == null
                ? decisions.previewDecision(
                      candidateRequest,
                      modelFamily,
                      request.horizonDays(),
                      metricSelectionMode,
                      availableMetrics,
                      validation,
                      labelReadiness
                  )
                : existingDecision;
        double coverageRatio = coverageRatio(
            validation,
            request.deviceIds().size(),
            selectedMetrics.size()
        );
        return new CandidateDraft(
            kind,
            from,
            to,
            candidateScore(
                validation,
                labelReadiness,
                request.deviceIds().size(),
                selectedMetrics.size(),
                minSamples,
                maxGapSeconds
            ),
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
            explanation
        );
    }

    public TimeWindow failureRichWindow(
        UUID accountId,
        MlDatasetWorkbenchPreviewRequest request,
        List<String> selectedMetrics
    ) {
        int horizonDays =
            request.horizonDays() == null || request.horizonDays() < 1 ? 1 : request.horizonDays();
        Set<UUID> selectedDevices = new LinkedHashSet<>(request.deviceIds());
        MaintenanceOutcomeReadModel positive = maintenanceOutcomeRepository
            .findByAccountAndObservedRange(
                accountId,
                request.from(),
                request.to().plus(Duration.ofDays(horizonDays))
            )
            .stream()
            .filter(outcome -> selectedDevices.contains(outcome.getDeviceId()))
            .filter(outcome -> support.isPositive(support.labelType(outcome)))
            .findFirst()
            .orElse(null);
        if (positive == null || selectedMetrics.isEmpty()) {
            return new TimeWindow(
                request.from(),
                request.to(),
                "No failure-rich outcome was found in the searched range."
            );
        }
        Instant from = support.maxInstant(
            request.from(),
            positive.getObservedAt().minus(Duration.ofDays(horizonDays))
        );
        Instant to = support.minInstant(request.to(), positive.getObservedAt().plusSeconds(1));
        if (!to.isAfter(from)) {
            return new TimeWindow(
                request.from(),
                request.to(),
                "Failure-rich outcome exists, but it does not produce a valid candidate" + " range."
            );
        }
        return new TimeWindow(
            from,
            to,
            "Window includes confirmed failure or corrective maintenance evidence."
        );
    }

    public Instant recentWindowStart(Instant from, Instant to) {
        Duration range = Duration.between(from, to);
        Duration recent =
            range.compareTo(Duration.ofDays(7)) > 0 ? Duration.ofDays(7) : range.dividedBy(2);
        return support.maxInstant(from, to.minus(recent));
    }

    public double candidateScore(
        MlDatasetValidationResult validation,
        MlDatasetLabelReadinessDto labelReadiness,
        int deviceCount,
        int metricCount,
        int minSamples,
        long maxGapSeconds
    ) {
        double coverageScore = coverageRatio(validation, deviceCount, metricCount);
        long expectedRows = (long) Math.max(1, deviceCount) * Math.max(1, metricCount) * minSamples;
        double densityScore = Math.min(1.0, (double) validation.acceptedRows() / expectedRows);
        long gap = support.longQualityValue(validation.qualityProfile(), "maxSamplingGapSeconds");
        double gapScore =
            gap <= maxGapSeconds || gap == 0 ? 1.0 : Math.max(0.0, (double) maxGapSeconds / gap);
        double qualityScore = validation.rejectedRows() == 0 ? 1.0 : 0.25;
        double labelScore =
            labelReadiness == null
                ? 1.0
                : labelReadiness.positiveLabels() > 0
                  ? Math.min(1.0, labelReadiness.coverageRatio())
                  : 0.0;
        return (
            Math.round(
                (0.30 * coverageScore +
                    0.20 * densityScore +
                    0.15 * gapScore +
                    0.15 * qualityScore +
                    0.20 * labelScore) *
                    100.0
            ) / 100.0
        );
    }

    public double coverageRatio(
        MlDatasetValidationResult validation,
        int deviceCount,
        int metricCount
    ) {
        if (deviceCount == 0 || metricCount == 0) {
            return 0.0;
        }
        Map<String, Long> coverageByDeviceMetric = support.longMapFromQualityProfile(
            validation.qualityProfile(),
            "coverageByDeviceMetric"
        );
        long coveredPairs = coverageByDeviceMetric
            .values()
            .stream()
            .filter(value -> value > 0)
            .count();
        return Math.min(1.0, (double) coveredPairs / ((long) deviceCount * metricCount));
    }

    public record CandidateDraft(
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
        String explanation
    ) {}

    public record TimeWindow(Instant from, Instant to, String explanation) {}
}
