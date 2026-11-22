package com.voltcanary.ingestion.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.common.events.telemetry.TelemetryReceivedEvent;
import com.voltcanary.ingestion.config.IngestionProperties;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Clock;

@Service
@ConditionalOnProperty(
        name = "app.idempotency.redis-enabled",
        havingValue = "false",
        matchIfMissing = true)
class InMemoryIdempotencyService implements IdempotencyService {
    private final LocalDeliveryReservations deliveries;

    InMemoryIdempotencyService(ObjectMapper mapper, IngestionProperties properties) {
        deliveries = new LocalDeliveryReservations(mapper, properties, Clock.systemUTC());
    }

    @Override
    public Reservation reserve(TelemetryReceivedEvent candidate) {
        return deliveries.reserve(candidate);
    }

    @Override
    public void complete(Reservation reservation) {
        deliveries.complete(reservation);
    }

    @Override
    public void release(Reservation reservation) {
        deliveries.release(reservation);
    }
}
