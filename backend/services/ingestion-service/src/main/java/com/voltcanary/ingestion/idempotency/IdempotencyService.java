package com.voltcanary.ingestion.idempotency;

import com.voltcanary.common.events.telemetry.TelemetryReceivedEvent;

import java.util.UUID;

/** Retains the original envelope so an uncertain Kafka outcome can be retried exactly. */
public interface IdempotencyService {
    final class Unavailable extends IllegalStateException {
        public Unavailable(Throwable cause) {
            super("Telemetry delivery state unavailable; retry the original reading", cause);
        }
    }

    enum Status {
        ACQUIRED,
        PUBLISHED,
        IN_FLIGHT
    }

    /** Only ACQUIRED authorizes publication of the retained envelope. */
    record Reservation(String key, UUID owner, Status status, TelemetryReceivedEvent event) {}

    Reservation reserve(TelemetryReceivedEvent candidate);

    void complete(Reservation reservation);

    void release(Reservation reservation);
}
