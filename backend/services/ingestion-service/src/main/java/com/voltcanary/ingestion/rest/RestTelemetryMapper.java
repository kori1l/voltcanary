package com.voltcanary.ingestion.rest;

import com.voltcanary.ingestion.dto.TelemetryIngestionRequest;
import com.voltcanary.ingestion.pipeline.IncomingTelemetryMessage;
import com.voltcanary.ingestion.pipeline.IngestionProtocol;
import org.springframework.stereotype.Component;

@Component
public class RestTelemetryMapper {

    public IncomingTelemetryMessage map(
            TelemetryIngestionRequest request,
            String sourceToken,
            String correlationId,
            String clientIp,
            boolean historicalImport) {
        return new IncomingTelemetryMessage(
                request.sourceId(),
                request.timestamp(),
                request.metrics(),
                IngestionProtocol.HTTP,
                null,
                correlationId,
                sourceToken,
                request.deviceId(),
                clientIp,
                historicalImport);
    }
}
