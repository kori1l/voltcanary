package com.voltcanary.ingestion.adapter.http;

import com.voltcanary.ingestion.dto.TelemetryIngestionRequest;

public record HttpAdapterRequest(
        TelemetryIngestionRequest reading,
        String sourceToken,
        String correlationId,
        String clientIp,
        boolean historicalImport) {
}
