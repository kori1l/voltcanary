package com.voltcanary.ingestion.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record BatchTelemetryIngestionRequest(
        @NotEmpty List<@Valid TelemetryIngestionRequest> readings) {
}
