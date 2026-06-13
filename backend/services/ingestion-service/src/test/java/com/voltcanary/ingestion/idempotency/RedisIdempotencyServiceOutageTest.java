package com.voltcanary.ingestion.idempotency;

import com.voltcanary.ingestion.config.IngestionProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisIdempotencyServiceOutageTest {

    @Test
    void markIfNewFailsClosedWhenRedisIsUnavailable() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> operations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(operations);
        when(operations.setIfAbsent(anyString(), anyString(), any()))
                .thenThrow(new RedisConnectionFailureException("redis unavailable"));
        RedisIdempotencyService service = new RedisIdempotencyService(
                redisTemplate,
                new IngestionProperties(),
                new SimpleMeterRegistry());

        assertThatThrownBy(() -> service.markIfNew(
                UUID.randomUUID(),
                "plug-001",
                Instant.parse("2026-06-13T08:00:00Z"),
                Map.of("activePowerW", 840.2)))
                .isInstanceOf(RedisConnectionFailureException.class)
                .hasMessageContaining("redis unavailable");
        verify(redisTemplate, never()).delete(anyString());
    }
}
