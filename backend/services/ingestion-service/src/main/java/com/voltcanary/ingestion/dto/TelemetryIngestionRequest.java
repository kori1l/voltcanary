package com.voltcanary.ingestion.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record TelemetryIngestionRequest(
        @NotNull UUID deviceId,
        String sourceId,
        @NotNull Instant timestamp,
        @NotEmpty Map<String, Object> metrics) {
}
