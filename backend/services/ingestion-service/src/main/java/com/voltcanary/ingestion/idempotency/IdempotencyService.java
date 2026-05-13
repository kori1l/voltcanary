package com.voltcanary.ingestion.idempotency;

import java.time.Instant;
import java.util.UUID;

public interface IdempotencyService {

    boolean markIfNew(UUID deviceId, String sourceId, Instant timestamp);
}
