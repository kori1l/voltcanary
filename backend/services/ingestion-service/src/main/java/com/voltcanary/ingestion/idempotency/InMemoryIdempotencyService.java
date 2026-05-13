package com.voltcanary.ingestion.idempotency;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "app.idempotency.redis-enabled", havingValue = "false", matchIfMissing = true)
class InMemoryIdempotencyService implements IdempotencyService {

    private final Set<String> keys = ConcurrentHashMap.newKeySet();

    @Override
    public boolean markIfNew(UUID deviceId, String sourceId, Instant timestamp) {
        return keys.add(key(deviceId, sourceId, timestamp));
    }

    private String key(UUID deviceId, String sourceId, Instant timestamp) {
        return deviceId + ":" + sourceId + ":" + timestamp;
    }
}
