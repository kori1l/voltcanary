package com.voltcanary.ingestion.ingress;

import com.voltcanary.ingestion.adapter.api.AdapterType;
import com.voltcanary.ingestion.adapter.api.ProtocolType;
import com.voltcanary.ingestion.adapter.api.SourceRuntimeConfig;
import com.voltcanary.ingestion.adapter.api.TelemetryIngressDispatcher;
import com.voltcanary.ingestion.adapter.api.TransportType;
import com.voltcanary.ingestion.adapter.http.HttpAdapterRequest;
import com.voltcanary.ingestion.canonical.IncomingTelemetryMessage;
import com.voltcanary.ingestion.config.IngestionProperties;
import com.voltcanary.ingestion.dto.BatchIngestionItemResponse;
import com.voltcanary.ingestion.dto.IngestionAcceptedResponse;
import com.voltcanary.ingestion.dto.TelemetryIngestionRequest;
import com.voltcanary.ingestion.pipeline.IngestionResult;
import com.voltcanary.ingestion.pipeline.TelemetryIngestionPipeline;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class IngressOrchestrator {

    private final TelemetryIngestionPipeline pipeline;
    private final TelemetryIngressDispatcher ingressDispatcher;
    private final IngestionProperties properties;
    private final MeterRegistry meterRegistry;

    public IngressOrchestrator(
            TelemetryIngestionPipeline pipeline,
            TelemetryIngressDispatcher ingressDispatcher,
            IngestionProperties properties,
            MeterRegistry meterRegistry) {
        this.pipeline = pipeline;
        this.ingressDispatcher = ingressDispatcher;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    public IngestionAcceptedResponse ingestHttp(
            TelemetryIngestionRequest request,
            String sourceToken,
            String correlationId,
            String clientIp,
            boolean historicalImport) {
        IngestionResult result = dispatch(
                AdapterType.GENERIC_HTTP_PUSH,
                ProtocolType.REST,
                TransportType.HTTP,
                HttpAdapterRequest.class,
                new HttpAdapterRequest(request, sourceToken, correlationId, clientIp, historicalImport),
                null);
        if (result.accepted()) {
            return IngestionAcceptedResponse.single("accepted", 1, 0, result.eventId());
        }
        return IngestionAcceptedResponse.single("duplicate", 0, 1, result.eventId());
    }

    public IngestionAcceptedResponse ingestHttpBatch(
            Collection<TelemetryIngestionRequest> requests,
            String sourceToken,
            String correlationId,
            String clientIp,
            boolean historicalImport) {
        if (requests.size() > properties.getMaxReadingsPerBatch()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Batch size exceeds configured limit");
        }
        meterRegistry.summary("voltcanary.ingestion.batch.size").record(requests.size());

        List<HttpAdapterRequest> adapterRequests = requests.stream()
                .map(request -> new HttpAdapterRequest(request, sourceToken, correlationId, clientIp, historicalImport))
                .toList();
        List<IncomingTelemetryMessage> messages = adapterRequests.stream()
                .map(adapterRequest -> ingressDispatcher.dispatch(
                        AdapterType.GENERIC_HTTP_PUSH,
                        ProtocolType.REST,
                        TransportType.HTTP,
                        HttpAdapterRequest.class,
                        adapterRequest,
                        null))
                .toList();

        int index = 0;
        int accepted = 0;
        int duplicate = 0;
        int rejected = 0;
        List<BatchIngestionItemResponse> items = new ArrayList<>();
        for (var message : messages) {
            try {
                IngestionResult result = pipeline.ingest(message);
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

    public <T> IngestionResult dispatch(
            AdapterType adapterType,
            ProtocolType protocol,
            TransportType transport,
            Class<T> requestType,
            T request,
            SourceRuntimeConfig sourceRuntimeConfig) {
        return pipeline.ingest(ingressDispatcher.dispatch(
                adapterType,
                protocol,
                transport,
                requestType,
                request,
                sourceRuntimeConfig));
    }
}
