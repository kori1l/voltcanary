package com.voltcanary.device.configurable.port;

import java.time.Instant;
import java.util.UUID;

public interface NativeHistoryLeases {
    record Lease(UUID id, UUID datasetId, UUID generation, long throughDataVersion, Instant expiresAt, String status) { }

    Lease acquire(UUID account, UUID actor, UUID dataset, UUID generation, long through, int durationSeconds, String key);
    Lease release(UUID account, UUID actor, UUID dataset, UUID lease);
}
