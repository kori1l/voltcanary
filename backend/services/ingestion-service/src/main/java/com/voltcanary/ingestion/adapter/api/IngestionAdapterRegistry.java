package com.voltcanary.ingestion.adapter.api;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface IngestionAdapterRegistry {

    Collection<TelemetryAdapter> adapters();

    Optional<TelemetryAdapter> find(AdapterType adapterType);

    Optional<TelemetryAdapter> find(AdapterType adapterType, ProtocolType protocol, TransportType transport);

    <T extends TelemetryAdapter> List<T> findByCapability(AdapterCapability capability, Class<T> adapterClass);
}
