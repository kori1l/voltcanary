package com.voltcanary.ingestion.connector;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryConnectorLeaseManager implements ConnectorLeaseManager {

    private final Map<UUID, Lease> leases = new ConcurrentHashMap<>();
    private final ConnectorRuntimeProperties properties;
    private final Clock clock;

    public InMemoryConnectorLeaseManager(ConnectorRuntimeProperties properties) {
        this(properties, Clock.systemUTC());
    }

    public InMemoryConnectorLeaseManager(ConnectorRuntimeProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public boolean acquire(UUID dataSourceId) {
        Instant now = clock.instant();
        leases.compute(dataSourceId, (ignored, existing) -> {
            if (existing == null || existing.expiresAt().isBefore(now) || ownedByThisInstance(existing)) {
                return new Lease(properties.getInstanceId(), now.plus(properties.getLeaseTtl()));
            }
            return existing;
        });
        return isOwned(dataSourceId);
    }

    @Override
    public boolean renew(UUID dataSourceId) {
        Instant now = clock.instant();
        leases.computeIfPresent(dataSourceId, (ignored, existing) -> {
            if (ownedByThisInstance(existing) && existing.expiresAt().isAfter(now)) {
                return new Lease(properties.getInstanceId(), now.plus(properties.getLeaseTtl()));
            }
            return existing;
        });
        return isOwned(dataSourceId);
    }

    @Override
    public void release(UUID dataSourceId) {
        leases.computeIfPresent(dataSourceId, (ignored, existing) ->
                ownedByThisInstance(existing) ? null : existing);
    }

    @Override
    public boolean isOwned(UUID dataSourceId) {
        Lease lease = leases.get(dataSourceId);
        return lease != null
                && ownedByThisInstance(lease)
                && lease.expiresAt().isAfter(clock.instant());
    }

    private boolean ownedByThisInstance(Lease lease) {
        return properties.getInstanceId().equals(lease.ownerId());
    }

    private record Lease(String ownerId, Instant expiresAt) {
    }
}
