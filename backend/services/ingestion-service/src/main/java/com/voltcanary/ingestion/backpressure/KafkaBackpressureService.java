package com.voltcanary.ingestion.backpressure;

import com.voltcanary.ingestion.config.IngestionProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class KafkaBackpressureService {

    private final IngestionProperties properties;
    private final Clock clock;
    private final AtomicReference<Instant> rejectUntil = new AtomicReference<>(Instant.EPOCH);

    @Autowired
    public KafkaBackpressureService(IngestionProperties properties) {
        this(properties, Clock.systemUTC());
    }

    KafkaBackpressureService(IngestionProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public void check() {
        if (!properties.isBackpressureEnabled()) {
            return;
        }
        if (clock.instant().isBefore(rejectUntil.get())) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Telemetry broker is under backpressure; retry later");
        }
    }

    public void markPublishFailure() {
        if (properties.isBackpressureEnabled()) {
            rejectUntil.set(clock.instant().plus(properties.getBackpressureCooldown()));
        }
    }

    public void markPublishSuccess() {
        rejectUntil.set(Instant.EPOCH);
    }
}
