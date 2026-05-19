package com.voltcanary.ingestion.service;

import com.voltcanary.ingestion.dto.TelemetryIngestionRequest;
import com.voltcanary.ingestion.pipeline.TelemetryIngestionPipeline;
import com.voltcanary.ingestion.rest.RestTelemetryMapper;
import java.util.Collection;
import org.springframework.stereotype.Service;

@Service
public class IngestionService {

    private final TelemetryIngestionPipeline pipeline;
    private final RestTelemetryMapper restTelemetryMapper;

    public IngestionService(TelemetryIngestionPipeline pipeline, RestTelemetryMapper restTelemetryMapper) {
        this.pipeline = pipeline;
        this.restTelemetryMapper = restTelemetryMapper;
    }

    public void ingest(TelemetryIngestionRequest request, String sourceToken, String correlationId) {
        pipeline.ingest(restTelemetryMapper.map(request, sourceToken, correlationId));
    }

    public void ingestBatch(Collection<TelemetryIngestionRequest> requests, String sourceToken, String correlationId) {
        requests.forEach(request -> ingest(request, sourceToken, correlationId));
    }
}
