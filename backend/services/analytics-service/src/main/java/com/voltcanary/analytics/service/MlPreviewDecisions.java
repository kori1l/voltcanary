package com.voltcanary.analytics.service;

import com.voltcanary.analytics.domain.MlModelFamily;
import com.voltcanary.analytics.dto.MlDatasetLabelReadinessDto;
import com.voltcanary.analytics.dto.MlDatasetMetricCoverageDto;
import com.voltcanary.analytics.dto.MlDatasetQualityGateDto;
import com.voltcanary.analytics.dto.MlInternalDatasetRequest;
import com.voltcanary.analytics.dto.MlMetricSelectionMode;
import com.voltcanary.analytics.dto.MlWorkbenchGateStatus;
import com.voltcanary.analytics.dto.MlWorkbenchStatus;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class MlPreviewDecisions {
    private final MlDatasetSupportService support;

    public PreviewDecision previewDecision(
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
        long selectedMetricsWithCoverage =
                request.metrics().stream()
                        .filter(metric -> coverageByMetric.containsKey(metric))
                        .count();
        for (String metric : request.metrics()) {
            MlDatasetMetricCoverageDto coverage = coverageByMetric.get(metric);
            if (coverage == null) {
                blockingReasons.add(
                        "Selected metric " + metric + " is not available for the selected scope.");
                continue;
            }
            if (metricSelectionMode == MlMetricSelectionMode.COMMON
                    && coverage.devicesWithMetric() < coverage.selectedDeviceCount()) {
                blockingReasons.add(
                        "Selected metric " + metric + " is not common to every selected device.");
            }
            if (metricSelectionMode == MlMetricSelectionMode.ALL_AVAILABLE
                    && coverage.devicesWithMetric() < coverage.selectedDeviceCount()) {
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

        qualityGates.add(
                new MlDatasetQualityGateDto(
                        "selectedMetrics",
                        "Selected metrics",
                        request.metrics().isEmpty()
                                ? MlWorkbenchGateStatus.BLOCK
                                : MlWorkbenchGateStatus.PASS,
                        request.metrics().size(),
                        1,
                        true,
                        request.metrics().isEmpty()
                                ? "Select at least one telemetry metric."
                                : "Metrics are selected."));
        qualityGates.add(
                new MlDatasetQualityGateDto(
                        "metricCoverage",
                        "Metric coverage",
                        selectedMetricsWithCoverage == request.metrics().size()
                                ? metricSelectionMode == MlMetricSelectionMode.ALL_AVAILABLE
                                                && request.metrics().stream()
                                                        .map(coverageByMetric::get)
                                                        .filter(metric -> metric != null)
                                                        .anyMatch(
                                                                metric ->
                                                                        metric.devicesWithMetric()
                                                                                < metric
                                                                                        .selectedDeviceCount())
                                        ? MlWorkbenchGateStatus.WARN
                                        : MlWorkbenchGateStatus.PASS
                                : MlWorkbenchGateStatus.BLOCK,
                        selectedMetricsWithCoverage,
                        request.metrics().size(),
                        true,
                        selectedMetricsWithCoverage == request.metrics().size()
                                ? "Selected metrics have telemetry coverage."
                                : "One or more selected metrics are unavailable."));
        qualityGates.add(
                new MlDatasetQualityGateDto(
                        "minSamplesPerMetric",
                        "Minimum samples per metric",
                        validation.ready()
                                ? MlWorkbenchGateStatus.PASS
                                : MlWorkbenchGateStatus.BLOCK,
                        validation.acceptedRows(),
                        (long) support.minSamplesPerMetric(request.minSamplesPerMetric())
                                * Math.max(1, request.metrics().size()),
                        true,
                        validation.ready()
                                ? "Selected metrics have enough samples."
                                : "Selected metrics do not meet minimum sample gates."));
        long observedGap =
                support.longQualityValue(validation.qualityProfile(), "maxSamplingGapSeconds");
        qualityGates.add(
                new MlDatasetQualityGateDto(
                        "maxSamplingGapSeconds",
                        "Maximum sampling gap",
                        observedGap > support.maxSamplingGapSeconds(request.maxSamplingGapSeconds())
                                ? MlWorkbenchGateStatus.WARN
                                : MlWorkbenchGateStatus.PASS,
                        observedGap,
                        support.maxSamplingGapSeconds(request.maxSamplingGapSeconds()),
                        false,
                        observedGap > support.maxSamplingGapSeconds(request.maxSamplingGapSeconds())
                                ? "Selected telemetry has sampling gaps above the warning"
                                        + " threshold."
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
                labelMessage =
                        "Failure prediction preview has no confirmed failure or corrective"
                                + " maintenance labels.";
                blockingReasons.add(labelMessage);
            } else if (labelReadiness.status() == MlWorkbenchStatus.READY_WITH_WARNINGS) {
                labelGateStatus = MlWorkbenchGateStatus.WARN;
                labelMessage = "Failure label readiness has weak coverage or class balance.";
                warningReasons.add(labelMessage);
            } else {
                labelGateStatus = MlWorkbenchGateStatus.PASS;
                labelMessage = "Failure label readiness meets preview gates.";
            }
            qualityGates.add(
                    new MlDatasetQualityGateDto(
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
        MlWorkbenchStatus status =
                distinctBlocks.isEmpty()
                        ? distinctWarnings.isEmpty()
                                ? MlWorkbenchStatus.READY
                                : MlWorkbenchStatus.READY_WITH_WARNINGS
                        : MlWorkbenchStatus.BLOCKED;
        return new PreviewDecision(
                status, distinctBlocks, distinctWarnings, List.copyOf(qualityGates));
    }

    public record PreviewDecision(
            MlWorkbenchStatus status,
            List<String> blockingReasons,
            List<String> warningReasons,
            List<MlDatasetQualityGateDto> qualityGates) {}
}
