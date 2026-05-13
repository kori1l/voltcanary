package com.voltcanary.ingestion.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record TelemetryIngestionRequest(
        @NotNull UUID deviceId,
        @NotBlank String sourceId,
        @NotNull Instant timestamp,
        @NotEmpty Map<String, Object> metrics) {
}
