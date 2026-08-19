package com.voltcanary.analytics.service;

import com.voltcanary.analytics.domain.MaintenanceWindowReadModel;
import com.voltcanary.analytics.dto.MlDatasetWorkbenchPreviewRequest;
import com.voltcanary.analytics.dto.MlInternalDatasetRequest;
import com.voltcanary.analytics.dto.MlMetricSelectionMode;
import com.voltcanary.analytics.repository.MaintenanceWindowReadModelRepository;
import com.voltcanary.analytics.telemetry.TelemetryChartResponse;
import com.voltcanary.analytics.telemetry.TelemetryClient;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MlDatasetValidationService {

    private final MaintenanceWindowReadModelRepository maintenanceWindowRepository;
    private final TelemetryClient telemetryClient;
    private final MlDatasetSupportService support;

    public MlDatasetValidationService(
            MaintenanceWindowReadModelRepository maintenanceWindowRepository,
            TelemetryClient telemetryClient,
            MlDatasetSupportService support) {
        this.maintenanceWindowRepository = maintenanceWindowRepository;
        this.telemetryClient = telemetryClient;
        this.support = support;
    }

    public void validateCreateRequest(MlInternalDatasetRequest request) {
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

    public void validatePreviewRequest(MlDatasetWorkbenchPreviewRequest request) {
        if (!request.to().isAfter(request.from())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Dataset preview time window must end after it starts");
        }
        if (support.normalizedDeviceIds(request.deviceIds()).size() != request.deviceIds().size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Device ids must be unique");
        }
    }

    public MlDatasetValidationResult validateTelemetry(MlInternalDatasetRequest request) {
        int minSamples = support.minSamplesPerMetric(request.minSamplesPerMetric());
        long maxGapSeconds = support.maxSamplingGapSeconds(request.maxSamplingGapSeconds());
        MlMetricSelectionMode metricSelectionMode = support.normalizedMetricSelectionMode(request.metricSelectionMode());
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
        List<MlDatasetTelemetryRow> extractedRows = new ArrayList<>();

        for (UUID deviceId : support.normalizedDeviceIds(request.deviceIds())) {
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
                    support.increment(rejectedReasons, "UNSUPPORTED_OR_UNAVAILABLE_METRIC");
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
                duplicateTimestamps += support.duplicateTimestampCount(points);
                long nonFiniteValues = points.stream().filter(point -> !Double.isFinite(point.value())).count();
                if (nonFiniteValues > 0) {
                    rejectedRows += nonFiniteValues;
                    support.increment(rejectedReasons, "NON_FINITE_VALUES", nonFiniteValues);
                    messages.add("Device " + deviceId + " metric " + metric + " contains "
                            + nonFiniteValues + " non-finite values.");
                    continue;
                }
                if (points.size() < minSamples) {
                    rejectedRows += minSamples - points.size();
                    support.increment(rejectedReasons, "INSUFFICIENT_SAMPLES");
                    messages.add("Device " + deviceId + " metric " + metric + " has " + points.size()
                            + " samples; minimum is " + minSamples + ".");
                    continue;
                }
                long maxGap = support.maxGapSeconds(points);
                if (maxGap > maxGapSeconds) {
                    warnings++;
                    messages.add("Device " + deviceId + " metric " + metric + " max sampling gap is "
                            + maxGap + " seconds.");
                }
                maxSamplingGapObserved = Math.max(maxSamplingGapObserved, maxGap);
                acceptedRows += points.size();
                support.increment(mappingStats, "accepted:" + metric, points.size());
                coverageByDeviceMetric.put(deviceId + ":" + metric, (long) points.size());
                points.stream()
                        .sorted(Comparator.comparing(TelemetryChartResponse.Point::timestamp))
                        .map(point -> {
                            MlDatasetNormalizedMetric normalized = support.normalizeMetric(metric);
                            return new MlDatasetTelemetryRow(
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
            support.increment(rejectedReasons, "NO_MATCHING_TELEMETRY");
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
        return new MlDatasetValidationResult(acceptedRows > 0 && rejectedReasons.isEmpty(),
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
                                .comparing(MlDatasetTelemetryRow::deviceId)
                                .thenComparing(MlDatasetTelemetryRow::metricName)
                                .thenComparing(MlDatasetTelemetryRow::timestamp))
                        .toList());
    }
}
