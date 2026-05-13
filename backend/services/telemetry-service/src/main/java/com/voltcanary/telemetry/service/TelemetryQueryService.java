package com.voltcanary.telemetry.service;

import com.voltcanary.telemetry.dto.TelemetryQualityResponse;
import com.voltcanary.telemetry.dto.TelemetryReadingResponse;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class TelemetryQueryService {

    public List<TelemetryReadingResponse> getReadings(UUID deviceId) {
        return List.of();
    }

    public Optional<TelemetryReadingResponse> getLatest(UUID deviceId) {
        return Optional.empty();
    }

    public List<TelemetryQualityResponse> getQuality(UUID deviceId) {
        return List.of();
    }
}
