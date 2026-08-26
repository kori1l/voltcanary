package com.voltcanary.ingestion.adapter.registry;

import com.voltcanary.ingestion.adapter.api.AdapterCapability;
import com.voltcanary.ingestion.adapter.api.AdapterType;
import com.voltcanary.ingestion.adapter.api.InboundTelemetryAdapter;
import com.voltcanary.ingestion.adapter.api.IngestionAdapterRegistry;
import com.voltcanary.ingestion.adapter.api.ProtocolType;
import com.voltcanary.ingestion.adapter.api.SourceRuntimeConfig;
import com.voltcanary.ingestion.adapter.api.TelemetryAdapter;
import com.voltcanary.ingestion.adapter.api.TelemetryIngressDispatcher;
import com.voltcanary.ingestion.adapter.api.TransportType;
import com.voltcanary.ingestion.canonical.IncomingTelemetryMessage;
import org.springframework.stereotype.Component;

@Component
public class RegistryBackedTelemetryIngressDispatcher implements TelemetryIngressDispatcher {

    private final IngestionAdapterRegistry registry;

    public RegistryBackedTelemetryIngressDispatcher(IngestionAdapterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public <T> IncomingTelemetryMessage dispatch(
            AdapterType adapterType,
            ProtocolType protocol,
            TransportType transport,
            Class<T> requestType,
            T request,
            SourceRuntimeConfig sourceRuntimeConfig) {
        TelemetryAdapter adapter = registry.find(adapterType, protocol, transport)
                .filter(candidate -> candidate.manifest().supports(AdapterCapability.INBOUND))
                .orElseThrow(() -> new IllegalStateException(
                        "No inbound telemetry adapter registered for " + adapterType
                                + " protocol=" + protocol + " transport=" + transport));
        if (!(adapter instanceof InboundTelemetryAdapter<?> inboundAdapter)) {
            throw new IllegalStateException("Adapter " + adapter.adapterId() + " does not implement inbound mapping");
        }
        return map(inboundAdapter, requestType.cast(request), sourceRuntimeConfig);
    }

    @SuppressWarnings("unchecked")
    private static <T> IncomingTelemetryMessage map(
            InboundTelemetryAdapter<?> adapter,
            T request,
            SourceRuntimeConfig sourceRuntimeConfig) {
        return ((InboundTelemetryAdapter<T>) adapter).map(request, sourceRuntimeConfig);
    }
}
