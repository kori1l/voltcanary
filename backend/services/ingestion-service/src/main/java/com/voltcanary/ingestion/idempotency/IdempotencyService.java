package com.voltcanary.ingestion.idempotency;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public interface IdempotencyService {

    boolean markIfNew(UUID deviceId, String sourceId, Instant timestamp, Map<String, Object> metrics);

    void forget(UUID deviceId, String sourceId, Instant timestamp, Map<String, Object> metrics);
}
