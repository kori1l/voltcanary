package com.voltcanary.ingestion.source;

import com.voltcanary.common.events.device.SourceRuntimeConfigEvent;
import com.voltcanary.ingestion.adapter.api.AdapterType;
import com.voltcanary.ingestion.adapter.api.ProtocolType;
import com.voltcanary.ingestion.adapter.api.SourceRuntimeConfig;
import com.voltcanary.ingestion.adapter.api.TransportType;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class SourceRuntimeConfigStore {

    private final Map<UUID, Entry> byDataSourceId = new ConcurrentHashMap<>();

    public void apply(SourceRuntimeConfigEvent event) {
        if (event == null || event.dataSourceId() == null) {
            return;
        }
        byDataSourceId.compute(event.dataSourceId(), (ignored, existing) -> {
            if (existing != null && existing.config().version() > event.configVersion()) {
                return existing;
            }
            return new Entry(toConfig(event));
        });
    }

    public Optional<SourceRuntimeConfig> get(UUID dataSourceId) {
        return Optional.ofNullable(byDataSourceId.get(dataSourceId)).map(Entry::config);
    }

    public List<SourceRuntimeConfig> snapshot() {
        return byDataSourceId.values().stream()
                .map(Entry::config)
                .toList();
    }

    private SourceRuntimeConfig toConfig(SourceRuntimeConfigEvent event) {
        return new SourceRuntimeConfig(
                event.sourceId(),
                event.dataSourceId(),
                event.accountId(),
                event.deviceId(),
                parseEnum(AdapterType.class, event.adapterType(), "adapterType"),
                parseEnum(ProtocolType.class, event.protocol(), "protocol"),
                parseEnum(TransportType.class, event.transport(), "transport"),
                event.status(),
                event.mappingSchemaVersion(),
                event.adapterConfigSchemaVersion(),
                event.mapping(),
                event.adapterConfig(),
                event.credentialReferences(),
                event.homeRegion(),
                event.configVersion());
    }

    private static <T extends Enum<T>> T parseEnum(Class<T> enumType, String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return Enum.valueOf(enumType, value.trim().toUpperCase());
    }

    private record Entry(SourceRuntimeConfig config) {
    }
}
