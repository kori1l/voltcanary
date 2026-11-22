package com.voltcanary.ingestion.idempotency;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.common.events.telemetry.TelemetryReceivedEvent;
import com.voltcanary.ingestion.config.IngestionProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

class RedisIdempotencyServiceOutageTest {

    @Test
    void reserveFailsClosedWhenRedisIsUnavailable() {
        var redis = mock(StringRedisTemplate.class);
        when(
            redis.execute(
                eq(RedisIdempotencyService.RESERVE),
                anyList(),
                anyString(),
                anyString(),
                anyString(),
                anyString()
            )
        ).thenThrow(new RedisConnectionFailureException("redis unavailable"));
        var service = new RedisIdempotencyService(
            redis,
            new ObjectMapper().findAndRegisterModules(),
            new IngestionProperties(),
            new SimpleMeterRegistry()
        );
        var event = new TelemetryReceivedEvent(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "plug-001",
            Instant.parse("2026-06-13T08:00:00Z"),
            Map.of("activePowerW", 840.2),
            Instant.now()
        );
        assertThatThrownBy(() -> service.reserve(event))
            .isInstanceOf(IdempotencyService.Unavailable.class)
            .hasCauseInstanceOf(RedisConnectionFailureException.class);
    }
}
