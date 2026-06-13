package com.voltcanary.ingestion.source;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class SourceRegistryCache {

    private static final Duration TTL = Duration.ofMinutes(5);
    private final Map<String, Entry> byTokenPrefix = new ConcurrentHashMap<>();

    public Optional<InternalDataSourceResponse> getByTokenPrefix(String tokenPrefix) {
        Entry entry = byTokenPrefix.get(tokenPrefix);
        if (entry == null || entry.expiresAt().isBefore(Instant.now())) {
            byTokenPrefix.remove(tokenPrefix);
            return Optional.empty();
        }
        return Optional.of(entry.source());
    }

    public void putByTokenPrefix(String tokenPrefix, InternalDataSourceResponse source) {
        byTokenPrefix.put(tokenPrefix, new Entry(source, Instant.now().plus(TTL)));
    }

    public void evictByTokenPrefix(String tokenPrefix) {
        byTokenPrefix.remove(tokenPrefix);
    }

    public List<CachedSource> snapshot(int maxEntries) {
        if (maxEntries <= 0) {
            return List.of();
        }
        Instant now = Instant.now();
        return byTokenPrefix.entrySet().stream()
                .filter(entry -> {
                    boolean expired = entry.getValue().expiresAt().isBefore(now);
                    if (expired) {
                        byTokenPrefix.remove(entry.getKey(), entry.getValue());
                    }
                    return !expired;
                })
                .limit(maxEntries)
                .map(entry -> new CachedSource(entry.getKey(), entry.getValue().source()))
                .toList();
    }

    public int size() {
        return byTokenPrefix.size();
    }

    public record CachedSource(String tokenPrefix, InternalDataSourceResponse source) {
    }

    private record Entry(InternalDataSourceResponse source, Instant expiresAt) {
    }
}
