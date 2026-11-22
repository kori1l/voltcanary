package com.voltcanary.ingestion.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.common.events.telemetry.TelemetryReceivedEvent;
import com.voltcanary.ingestion.config.IngestionProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;

class RedisIdempotencyReservationTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

    private RedisIdempotencyService replica() {
        return new RedisIdempotencyService(
            redis,
            mapper,
            new IngestionProperties(),
            new SimpleMeterRegistry()
        );
    }

    @Test
    void replicasAddressTheSameIdentityAndReturnTheOriginalRetainedEnvelope() throws Exception {
        var original = event(UUID.randomUUID(), Instant.now());
        var retry = event(original.deviceId(), original.receivedAt().plusSeconds(1));
        String retained = mapper.writeValueAsString(original);
        when(
            redis.execute(
                eq(RedisIdempotencyService.RESERVE),
                anyList(),
                anyString(),
                anyString(),
                anyString(),
                anyString()
            )
        ).thenReturn(
            List.of("ACQUIRED", retained),
            List.of("IN_FLIGHT", retained),
            List.of("PUBLISHED", retained)
        );
        var first = replica().reserve(original);
        var concurrent = replica().reserve(retry);
        var completed = replica().reserve(retry);
        assertThat(concurrent.key()).isEqualTo(first.key());
        assertThat(completed.key()).isEqualTo(first.key());
        assertThat(concurrent.status()).isEqualTo(IdempotencyService.Status.IN_FLIGHT);
        assertThat(completed.status()).isEqualTo(IdempotencyService.Status.PUBLISHED);
        assertThat(completed.event()).isEqualTo(original);
        assertThat(concurrent.owner()).isNotEqualTo(first.owner());
        var keys = ArgumentCaptor.forClass(List.class);
        verify(redis, times(3)).execute(
            eq(RedisIdempotencyService.RESERVE),
            keys.capture(),
            anyString(),
            anyString(),
            eq("30000"),
            eq("86400000")
        );
        assertThat(keys.getAllValues()).allSatisfy(key ->
            assertThat(key).containsExactly(first.key())
        );
    }

    @Test
    void completionRequiresTheOriginalOwnerAndDoesNotAcceptAnExpiredLease() throws Exception {
        var event = event(UUID.randomUUID(), Instant.now());
        when(
            redis.execute(
                eq(RedisIdempotencyService.RESERVE),
                anyList(),
                anyString(),
                anyString(),
                anyString(),
                anyString()
            )
        ).thenReturn(List.of("ACQUIRED", mapper.writeValueAsString(event)));
        var service = replica();
        var reservation = service.reserve(event);
        when(
            redis.execute(
                RedisIdempotencyService.COMPLETE,
                List.of(reservation.key()),
                reservation.owner().toString(),
                "86400000"
            )
        ).thenReturn(0L);
        assertThatThrownBy(() -> service.complete(reservation)).isInstanceOf(
            IllegalStateException.class
        );
        service.release(reservation);
        verify(redis).execute(
            RedisIdempotencyService.RELEASE,
            List.of(reservation.key()),
            reservation.owner().toString()
        );
    }

    private TelemetryReceivedEvent event(UUID device, Instant receivedAt) {
        return new TelemetryReceivedEvent(
            UUID.randomUUID(),
            device,
            "plug-001",
            Instant.parse("2026-06-13T08:00:00Z"),
            Map.of("activePowerW", 840),
            receivedAt
        );
    }
}
