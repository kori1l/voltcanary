package com.voltcanary.ingestion.adapter.http;

import com.voltcanary.ingestion.adapter.api.AdapterCapabilities;
import com.voltcanary.ingestion.adapter.api.AdapterCapability;
import com.voltcanary.ingestion.adapter.api.AdapterManifest;
import com.voltcanary.ingestion.adapter.api.AdapterType;
import com.voltcanary.ingestion.adapter.api.InboundTelemetryAdapter;
import com.voltcanary.ingestion.adapter.api.ProtocolType;
import com.voltcanary.ingestion.adapter.api.SourceRuntimeConfig;
import com.voltcanary.ingestion.adapter.api.TransportType;
import com.voltcanary.ingestion.canonical.IngestionContext;
import com.voltcanary.ingestion.canonical.IncomingTelemetryMessage;
import com.voltcanary.ingestion.canonical.TelemetryOrigin;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class HttpTelemetryAdapter implements InboundTelemetryAdapter<HttpAdapterRequest> {

    private static final AdapterManifest MANIFEST = new AdapterManifest(
            "generic-http-push",
            AdapterType.GENERIC_HTTP_PUSH,
            "Generic HTTP Push",
            TransportType.HTTP,
            ProtocolType.REST,
            AdapterCapabilities.of(AdapterCapability.INBOUND),
            "v1");

    @Override
    public AdapterManifest manifest() {
        return MANIFEST;
    }

    @Override
    public IncomingTelemetryMessage map(HttpAdapterRequest request, SourceRuntimeConfig sourceRuntimeConfig) {
        return new IncomingTelemetryMessage(
                request.reading().sourceId(),
                request.reading().deviceId(),
                null,
                request.reading().timestamp(),
                Instant.now(),
                request.reading().metrics(),
                origin(),
                new IngestionContext(
                        request.correlationId(),
                        request.sourceToken(),
                        request.clientIp(),
                        Map.of()),
                request.historicalImport());
    }

    public List<IncomingTelemetryMessage> mapBatch(Collection<HttpAdapterRequest> requests) {
        return requests.stream()
                .map(request -> map(request, null))
                .toList();
    }

    private TelemetryOrigin origin() {
        return new TelemetryOrigin(
                MANIFEST.adapterType(),
                MANIFEST.protocol(),
                MANIFEST.transport(),
                MANIFEST.adapterId(),
                Map.of());
    }
}
