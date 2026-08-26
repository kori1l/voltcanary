package com.voltcanary.ingestion.source;

import com.voltcanary.common.events.device.SourceRuntimeConfigEvent;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class SourceRegistryCache {

    private final Map<String, Entry> byTokenPrefix = new ConcurrentHashMap<>();
    private final Map<String, Entry> byExternalId = new ConcurrentHashMap<>();
    private final Map<UUID, Entry> byDataSourceId = new ConcurrentHashMap<>();

    public Optional<InternalDataSourceResponse> getByTokenPrefix(String tokenPrefix) {
        return Optional.ofNullable(byTokenPrefix.get(tokenPrefix)).map(Entry::source);
    }

    public Optional<InternalDataSourceResponse> getByExternalId(String externalId) {
        return Optional.ofNullable(byExternalId.get(externalId)).map(Entry::source);
    }

    public Optional<InternalDataSourceResponse> getByDataSourceId(UUID dataSourceId) {
        return Optional.ofNullable(byDataSourceId.get(dataSourceId)).map(Entry::source);
    }

    public void putByTokenPrefix(String tokenPrefix, InternalDataSourceResponse source) {
        put(source, 0L);
    }

    public void apply(SourceRuntimeConfigEvent event) {
        if (event == null || event.dataSourceId() == null) {
            return;
        }
        Entry existing = byDataSourceId.get(event.dataSourceId());
        if (existing != null && existing.configVersion() > event.configVersion()) {
            return;
        }
        put(toSource(event), event.configVersion());
    }

    public void put(InternalDataSourceResponse source, long configVersion) {
        if (source == null || source.dataSourceId() == null) {
            return;
        }
        remove(source.dataSourceId());
        Entry entry = new Entry(source, configVersion);
        byDataSourceId.put(source.dataSourceId(), entry);
        if (source.externalId() != null && !source.externalId().isBlank()) {
            byExternalId.put(source.externalId(), entry);
        }
        if (source.tokenPrefix() != null && !source.tokenPrefix().isBlank()) {
            byTokenPrefix.put(source.tokenPrefix(), entry);
        }
    }

    public void evictByTokenPrefix(String tokenPrefix) {
        getByTokenPrefix(tokenPrefix).map(InternalDataSourceResponse::dataSourceId).ifPresent(this::remove);
    }

    public void remove(UUID dataSourceId) {
        Entry previous = byDataSourceId.remove(dataSourceId);
        if (previous == null) {
            return;
        }
        InternalDataSourceResponse source = previous.source();
        if (source.externalId() != null) {
            byExternalId.remove(source.externalId(), previous);
        }
        if (source.tokenPrefix() != null) {
            byTokenPrefix.remove(source.tokenPrefix(), previous);
        }
    }

    public List<CachedSource> snapshot(int maxEntries) {
        if (maxEntries <= 0) {
            return List.of();
        }
        return byDataSourceId.values().stream()
                .limit(maxEntries)
                .map(entry -> new CachedSource(entry.source().tokenPrefix(), entry.source()))
                .toList();
    }

    public int size() {
        return byDataSourceId.size();
    }

    public record CachedSource(String tokenPrefix, InternalDataSourceResponse source) {
    }

    private InternalDataSourceResponse toSource(SourceRuntimeConfigEvent event) {
        return new InternalDataSourceResponse(
                event.accountId(),
                event.deviceId(),
                event.dataSourceId(),
                event.sourceId(),
                event.adapterType(),
                event.protocol(),
                event.status(),
                event.tokenPrefix(),
                event.tokenHash());
    }

    private record Entry(InternalDataSourceResponse source, long configVersion) {
    }
}
