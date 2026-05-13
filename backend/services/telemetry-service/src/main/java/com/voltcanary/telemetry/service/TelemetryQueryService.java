package com.voltcanary.telemetry.service;

import com.voltcanary.telemetry.dto.TelemetryChartPointResponse;
import com.voltcanary.telemetry.dto.TelemetryChartResponse;
import com.voltcanary.telemetry.dto.TelemetryLatestResponse;
import com.voltcanary.telemetry.dto.TelemetryQualityResponse;
import com.voltcanary.telemetry.dto.TelemetryReadingResponse;
import com.voltcanary.telemetry.repository.TelemetryMeasurementRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class TelemetryQueryService {

    private final TelemetryMeasurementRepository measurementRepository;

    public TelemetryQueryService(TelemetryMeasurementRepository measurementRepository) {
        this.measurementRepository = measurementRepository;
    }

    public List<TelemetryReadingResponse> getReadings(UUID deviceId) {
        return List.of();
    }

    public Optional<TelemetryReadingResponse> getLatest(UUID deviceId) {
        return Optional.empty();
    }

    public Optional<TelemetryLatestResponse> latestMeasurements(UUID deviceId) {
        return measurementRepository.latestTimestamp(deviceId)
                .map(timestamp -> {
                    Map<String, Double> readings = new LinkedHashMap<>();
                    measurementRepository.findByDeviceIdAndTimestamp(deviceId, timestamp)
                            .forEach(measurement -> readings.put(measurement.metricName(), measurement.metricValue()));
                    return new TelemetryLatestResponse(deviceId, readings, timestamp);
                });
    }

    public TelemetryChartResponse chart(UUID deviceId, String metric, Instant from, Instant to) {
        List<TelemetryChartPointResponse> points = measurementRepository.findSeries(deviceId, metric, from, to).stream()
                .map(measurement -> new TelemetryChartPointResponse(
                        measurement.recordedAt(),
                        measurement.metricValue()))
                .toList();
        return new TelemetryChartResponse(deviceId, metric, points);
    }

    public List<TelemetryQualityResponse> getQuality(UUID deviceId) {
        return List.of();
    }
}
