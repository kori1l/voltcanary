package com.voltcanary.analytics.service;

import com.voltcanary.analytics.dto.MlDatasetMetricCoverageDto;
import com.voltcanary.analytics.dto.MlMetricSelectionMode;
import com.voltcanary.analytics.dto.MlWorkbenchStatus;
import com.voltcanary.analytics.telemetry.TelemetryChartResponse;
import com.voltcanary.analytics.telemetry.TelemetryClient;
import com.voltcanary.analytics.telemetry.TelemetryMetricResponse;
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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MlDatasetMetricCoverage {

    private final TelemetryClient telemetryClient;
    private final MlDatasetSupportService support;

    public List<MlDatasetMetricCoverageDto> availableMetrics(
        List<UUID> deviceIds,
        Instant from,
        Instant to,
        MlMetricSelectionMode metricSelectionMode,
        int minSamples,
        long maxGapSeconds
    ) {
        Map<UUID, Map<String, MetricMetadata>> metricsByDevice = metricMetadataByDevice(deviceIds);
        Set<String> metricNames = metricNamesForMode(metricsByDevice.values(), metricSelectionMode);
        return metricNames
            .stream()
            .sorted()
            .map(metricName ->
                metricCoverage(
                    metricName,
                    deviceIds,
                    metricsByDevice,
                    from,
                    to,
                    metricSelectionMode,
                    minSamples,
                    maxGapSeconds
                )
            )
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
                metrics.put(
                    metric.name(),
                    new MetricMetadata(
                        metric.name(),
                        support.blankToDefault(metric.displayName(), metric.name()),
                        normalized.canonicalMetricName(),
                        metric.unit() == null || metric.unit().isBlank()
                            ? normalized.canonicalUnit()
                            : metric.unit()
                    )
                );
            }
            value.put(deviceId, metrics);
        }
        return value;
    }

    private Set<String> metricNamesForMode(
        Collection<Map<String, MetricMetadata>> metricsByDevice,
        MlMetricSelectionMode metricSelectionMode
    ) {
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
        long maxGapSeconds
    ) {
        MetricMetadata metadata = metricsByDevice
            .values()
            .stream()
            .map(metrics -> metrics.get(metricName))
            .filter(item -> item != null)
            .findFirst()
            .orElseGet(() -> {
                MlDatasetNormalizedMetric normalized = support.normalizeMetric(metricName);
                return new MetricMetadata(
                    metricName,
                    metricName,
                    normalized.canonicalMetricName(),
                    normalized.canonicalUnit()
                );
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
            TelemetryChartResponse readings = telemetryClient.readings(
                deviceId,
                metricName,
                from,
                to
            );
            List<TelemetryChartResponse.Point> points =
                readings == null || readings.points() == null
                    ? List.of()
                    : readings
                          .points()
                          .stream()
                          .sorted(Comparator.comparing(TelemetryChartResponse.Point::timestamp))
                          .toList();
            sampleCount += points.size();
            maxGapObserved = Math.max(maxGapObserved, support.maxGapSeconds(points));
            duplicateTimestamps += support.duplicateTimestampCount(points);
            nonFinite += points
                .stream()
                .filter(point -> !Double.isFinite(point.value()))
                .count();
        }
        double coverageRatio = deviceIds.isEmpty()
            ? 0.0
            : (double) devicesWithMetric / deviceIds.size();
        double duplicateRate = sampleCount == 0 ? 0.0 : (double) duplicateTimestamps / sampleCount;
        if (devicesWithMetric == 0) {
            blockingReasons.add("Metric is not available on selected devices.");
        }
        if (
            metricSelectionMode == MlMetricSelectionMode.COMMON &&
            devicesWithMetric < deviceIds.size()
        ) {
            blockingReasons.add("Metric is not present on every selected device.");
        } else if (
            metricSelectionMode == MlMetricSelectionMode.ALL_AVAILABLE &&
            devicesWithMetric < deviceIds.size()
        ) {
            warningReasons.add(
                "Metric is available on only " +
                    devicesWithMetric +
                    " of " +
                    deviceIds.size() +
                    " selected devices."
            );
        }
        if (sampleCount == 0) {
            warningReasons.add("Metric has no samples in the selected window.");
        }
        if (sampleCount > 0 && sampleCount < (long) minSamples * Math.max(1, devicesWithMetric)) {
            blockingReasons.add(
                "Metric has fewer than " + minSamples + " samples per covered device."
            );
        }
        if (maxGapObserved > maxGapSeconds) {
            warningReasons.add("Metric max sampling gap exceeds " + maxGapSeconds + " seconds.");
        }
        if (nonFinite > 0) {
            blockingReasons.add("Metric contains non-finite values.");
        }
        MlWorkbenchStatus status = blockingReasons.isEmpty()
            ? warningReasons.isEmpty()
                ? MlWorkbenchStatus.READY
                : MlWorkbenchStatus.READY_WITH_WARNINGS
            : sampleCount == 0
              ? MlWorkbenchStatus.NO_DATA
              : MlWorkbenchStatus.BLOCKED;
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
            List.copyOf(warningReasons)
        );
    }

    private record MetricMetadata(
        String metricName,
        String displayName,
        String canonicalMetricName,
        String unit
    ) {}
}
