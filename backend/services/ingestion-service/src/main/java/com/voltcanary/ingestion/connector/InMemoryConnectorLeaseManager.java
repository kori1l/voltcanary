package com.voltcanary.ingestion.connector;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class InMemoryConnectorLeaseManager implements ConnectorLeaseManager {

    private final Map<UUID, Lease> leases = new ConcurrentHashMap<>();
    private final ConnectorRuntimeProperties properties;
    private final Clock clock;
    private final AtomicLong fencingSequence = new AtomicLong();

    public InMemoryConnectorLeaseManager(ConnectorRuntimeProperties properties) {
        this(properties, Clock.systemUTC());
    }

    public InMemoryConnectorLeaseManager(ConnectorRuntimeProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public Optional<ConnectorLease> acquire(UUID dataSourceId) {
        Instant now = clock.instant();
        leases.compute(dataSourceId, (ignored, existing) -> {
            if (existing == null || existing.expiresAt().isBefore(now) || ownedByThisInstance(existing)) {
                long fencingToken = existing != null && ownedByThisInstance(existing)
                        ? existing.fencingToken()
                        : fencingSequence.incrementAndGet();
                return new Lease(properties.getInstanceId(), fencingToken, now.plus(properties.getLeaseTtl()));
            }
            return existing;
        });
        Lease lease = leases.get(dataSourceId);
        if (lease == null || !ownedByThisInstance(lease) || lease.expiresAt().isBefore(clock.instant())) {
            return Optional.empty();
        }
        return Optional.of(new ConnectorLease(dataSourceId, lease.ownerId(), lease.fencingToken()));
    }

    @Override
    public Optional<ConnectorLease> renew(ConnectorLease currentLease) {
        Instant now = clock.instant();
        leases.computeIfPresent(currentLease.dataSourceId(), (ignored, existing) -> {
            if (matches(existing, currentLease) && existing.expiresAt().isAfter(now)) {
                return new Lease(existing.ownerId(), existing.fencingToken(), now.plus(properties.getLeaseTtl()));
            }
            return existing;
        });
        return isCurrent(currentLease) ? Optional.of(currentLease) : Optional.empty();
    }

    @Override
    public void release(ConnectorLease lease) {
        leases.computeIfPresent(lease.dataSourceId(), (ignored, existing) ->
                matches(existing, lease) ? null : existing);
    }

    @Override
    public boolean isCurrent(ConnectorLease lease) {
        Lease current = leases.get(lease.dataSourceId());
        return current != null
                && matches(current, lease)
                && current.expiresAt().isAfter(clock.instant());
    }

    @Override
    public Optional<ConnectorLease> current(UUID dataSourceId) {
        Lease lease = leases.get(dataSourceId);
        if (lease == null || !ownedByThisInstance(lease) || lease.expiresAt().isBefore(clock.instant())) {
            return Optional.empty();
        }
        return Optional.of(new ConnectorLease(dataSourceId, lease.ownerId(), lease.fencingToken()));
    }

    private boolean ownedByThisInstance(Lease lease) {
        return properties.getInstanceId().equals(lease.ownerId());
    }

    private boolean matches(Lease current, ConnectorLease lease) {
        return current != null
                && properties.getInstanceId().equals(lease.ownerId())
                && current.ownerId().equals(lease.ownerId())
                && current.fencingToken() == lease.fencingToken();
    }

    private record Lease(String ownerId, long fencingToken, Instant expiresAt) {
    }
}
