package com.voltcanary.ingestion.source;

import java.time.Duration;
import java.time.Instant;
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

    private record Entry(InternalDataSourceResponse source, Instant expiresAt) {
    }
}
