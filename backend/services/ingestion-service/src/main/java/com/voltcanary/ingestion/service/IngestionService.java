package com.voltcanary.ingestion.service;

import com.voltcanary.ingestion.config.IngestionProperties;
import com.voltcanary.ingestion.dto.BatchIngestionItemResponse;
import com.voltcanary.ingestion.dto.IngestionAcceptedResponse;
import com.voltcanary.ingestion.dto.TelemetryIngestionRequest;
import com.voltcanary.ingestion.pipeline.IngestionResult;
import com.voltcanary.ingestion.pipeline.TelemetryIngestionPipeline;
import com.voltcanary.ingestion.rest.RestTelemetryMapper;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
public class IngestionService {

    private final TelemetryIngestionPipeline pipeline;
    private final RestTelemetryMapper restTelemetryMapper;
    private final IngestionProperties properties;
    private final MeterRegistry meterRegistry;

    public IngestionService(
            TelemetryIngestionPipeline pipeline,
            RestTelemetryMapper restTelemetryMapper,
            IngestionProperties properties,
            MeterRegistry meterRegistry) {
        this.pipeline = pipeline;
        this.restTelemetryMapper = restTelemetryMapper;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    public IngestionAcceptedResponse ingest(
            TelemetryIngestionRequest request,
            String sourceToken,
            String correlationId,
            String clientIp,
            boolean historicalImport) {
        IngestionResult result = pipeline.ingest(restTelemetryMapper.map(
                request,
                sourceToken,
                correlationId,
                clientIp,
                historicalImport));
        if (result.accepted()) {
            return IngestionAcceptedResponse.single("accepted", 1, 0, result.eventId());
        }
        return IngestionAcceptedResponse.single("duplicate", 0, 1, result.eventId());
    }

    public IngestionAcceptedResponse ingestBatch(
            Collection<TelemetryIngestionRequest> requests,
            String sourceToken,
            String correlationId,
            String clientIp,
            boolean historicalImport) {
        if (requests.size() > properties.getMaxReadingsPerBatch()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Batch size exceeds configured limit");
        }
        meterRegistry.summary("voltcanary.ingestion.batch.size").record(requests.size());
        int index = 0;
        int accepted = 0;
        int duplicate = 0;
        int rejected = 0;
        List<BatchIngestionItemResponse> items = new ArrayList<>();
        for (TelemetryIngestionRequest request : requests) {
            try {
                IngestionResult result = pipeline.ingest(restTelemetryMapper.map(
                        request,
                        sourceToken,
                        correlationId,
                        clientIp,
                        historicalImport));
                if (result.accepted()) {
                    accepted++;
                    items.add(new BatchIngestionItemResponse(index, "accepted", result.eventId(), null));
                } else {
                    duplicate++;
                    items.add(new BatchIngestionItemResponse(index, "duplicate", result.eventId(), result.message()));
                }
            } catch (ResponseStatusException exception) {
                rejected++;
                items.add(new BatchIngestionItemResponse(index, "rejected", null, exception.getReason()));
            }
            index++;
        }
        String status = rejected == 0 ? "accepted" : accepted == 0 && duplicate == 0 ? "rejected" : "partial";
        return IngestionAcceptedResponse.batch(status, accepted, duplicate, rejected, List.copyOf(items));
    }
}
