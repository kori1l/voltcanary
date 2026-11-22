package com.voltcanary.ingestion.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.common.events.telemetry.TelemetryReceivedEvent;
import com.voltcanary.ingestion.config.IngestionProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

final class LocalDeliveryReservations implements IdempotencyService {

    private sealed interface Delivery {
        Instant expiresAt();
    }

    private record Pending(
        TelemetryReceivedEvent event,
        UUID owner,
        Instant leaseUntil,
        Instant expiresAt
    ) implements Delivery {}

    private record Published(Instant expiresAt) implements Delivery {}

    private final Map<String, Delivery> deliveries = new HashMap<>();
    private final ObjectMapper mapper;
    private final Duration retention;
    private final Duration lease;
    private final Clock clock;
    private Instant nextCleanup = Instant.MIN;

    LocalDeliveryReservations(ObjectMapper mapper, IngestionProperties properties, Clock clock) {
        this.mapper = mapper
            .copy()
            .enable(
                com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS
            )
            .setNodeFactory(
                com.fasterxml.jackson.databind.node.JsonNodeFactory.withExactBigDecimals(true)
            );
        this.retention = properties.getIdempotencyTtl();
        this.lease = properties.getIdempotencyLease();
        DeliveryRetention.validate(retention, lease, properties.getKafkaAckTimeout());
        this.clock = clock;
    }

    @Override
    public synchronized Reservation reserve(TelemetryReceivedEvent candidate) {
        Instant now = clock.instant();
        if (!now.isBefore(nextCleanup)) {
            deliveries.values().removeIf(delivery -> !now.isBefore(delivery.expiresAt()));
            nextCleanup = now.plusSeconds(60);
        }
        String key = TelemetryIdentity.key(mapper, candidate);
        Delivery existing = deliveries.get(key);
        UUID owner = UUID.randomUUID();
        if (existing != null && now.isBefore(existing.expiresAt())) {
            if (existing instanceof Published) return new Reservation(
                key,
                owner,
                Status.PUBLISHED,
                candidate
            );
            var pending = (Pending) existing;
            if (now.isBefore(pending.leaseUntil())) return new Reservation(
                key,
                owner,
                Status.IN_FLIGHT,
                pending.event()
            );
            candidate = pending.event();
        }
        // Copy nested metric maps as well as the envelope, like the Redis serialization boundary.
        candidate = mapper.convertValue(
            mapper.valueToTree(candidate),
            TelemetryReceivedEvent.class
        );
        deliveries.put(key, new Pending(candidate, owner, now.plus(lease), now.plus(retention)));
        return new Reservation(key, owner, Status.ACQUIRED, candidate);
    }

    @Override
    public synchronized void complete(Reservation reservation) {
        Delivery delivery = deliveries.get(reservation.key());
        Instant now = clock.instant();
        if (
            !(delivery instanceof Pending pending) ||
            !pending.owner().equals(reservation.owner()) ||
            !now.isBefore(pending.leaseUntil()) ||
            !now.isBefore(pending.expiresAt())
        ) {
            throw new Unavailable(
                new IllegalStateException("Delivery reservation expired or changed")
            );
        }
        // Once acknowledged, only the compact identity marker needs to survive the retention
        // window.
        deliveries.put(reservation.key(), new Published(now.plus(retention)));
    }

    @Override
    public synchronized void release(Reservation reservation) {
        Delivery delivery = deliveries.get(reservation.key());
        if (delivery instanceof Pending pending && pending.owner().equals(reservation.owner())) {
            deliveries.put(
                reservation.key(),
                new Pending(pending.event(), pending.owner(), Instant.MIN, pending.expiresAt())
            );
        }
    }
}
