package com.voltcanary.ingestion.adapter.registry;

import com.voltcanary.ingestion.adapter.api.AdapterCapability;
import com.voltcanary.ingestion.adapter.api.AdapterType;
import com.voltcanary.ingestion.adapter.api.IngestionAdapterRegistry;
import com.voltcanary.ingestion.adapter.api.ProtocolType;
import com.voltcanary.ingestion.adapter.api.TelemetryAdapter;
import com.voltcanary.ingestion.adapter.api.TransportType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class DefaultIngestionAdapterRegistry implements IngestionAdapterRegistry {

    private final Map<AdapterType, TelemetryAdapter> adaptersByType;
    private final Map<String, TelemetryAdapter> adaptersById;

    public DefaultIngestionAdapterRegistry(Collection<TelemetryAdapter> adapters) {
        List<TelemetryAdapter> sortedAdapters = new ArrayList<>(adapters);
        sortedAdapters.sort(Comparator.comparing(TelemetryAdapter::adapterId));
        this.adaptersByType = new LinkedHashMap<>();
        this.adaptersById = new LinkedHashMap<>();
        for (TelemetryAdapter adapter : sortedAdapters) {
            register(adapter);
        }
    }

    @Override
    public Collection<TelemetryAdapter> adapters() {
        return List.copyOf(adaptersByType.values());
    }

    @Override
    public Optional<TelemetryAdapter> find(AdapterType adapterType) {
        return Optional.ofNullable(adaptersByType.get(adapterType));
    }

    @Override
    public Optional<TelemetryAdapter> find(AdapterType adapterType, ProtocolType protocol, TransportType transport) {
        return find(adapterType)
                .filter(adapter -> adapter.manifest().protocol() == protocol)
                .filter(adapter -> adapter.manifest().transport() == transport);
    }

    @Override
    public <T extends TelemetryAdapter> List<T> findByCapability(AdapterCapability capability, Class<T> adapterClass) {
        return adaptersByType.values().stream()
                .filter(adapter -> adapter.manifest().supports(capability))
                .filter(adapterClass::isInstance)
                .map(adapterClass::cast)
                .toList();
    }

    private void register(TelemetryAdapter adapter) {
        TelemetryAdapter previousType = adaptersByType.putIfAbsent(adapter.adapterType(), adapter);
        if (previousType != null) {
            throw new IllegalArgumentException("Duplicate ingestion adapter type " + adapter.adapterType());
        }
        TelemetryAdapter previousId = adaptersById.putIfAbsent(adapter.adapterId(), adapter);
        if (previousId != null) {
            throw new IllegalArgumentException("Duplicate ingestion adapter id " + adapter.adapterId());
        }
    }
}
