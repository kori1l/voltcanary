package com.voltcanary.ingestion.idempotency;

import com.voltcanary.ingestion.config.IngestionProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisIdempotencySharedStateTest {

    @Test
    void duplicateTelemetryIsDetectedAcrossServiceInstances() {
        StringRedisTemplate redisTemplate = redisTemplateWithSharedKeys();
        RedisIdempotencyService firstReplica = new RedisIdempotencyService(
                redisTemplate,
                new IngestionProperties(),
                new SimpleMeterRegistry());
        RedisIdempotencyService secondReplica = new RedisIdempotencyService(
                redisTemplate,
                new IngestionProperties(),
                new SimpleMeterRegistry());
        UUID deviceId = UUID.randomUUID();
        Instant timestamp = Instant.parse("2026-06-13T08:00:00Z");
        Map<String, Object> metrics = Map.of("activePowerW", 840.2);

        assertThat(firstReplica.markIfNew(deviceId, "plug-001", timestamp, metrics)).isTrue();
        assertThat(secondReplica.markIfNew(deviceId, "plug-001", timestamp, metrics)).isFalse();
    }

    private StringRedisTemplate redisTemplateWithSharedKeys() {
        Set<String> keys = ConcurrentHashMap.newKeySet();
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> operations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(operations);
        when(operations.setIfAbsent(anyString(), anyString(), any())).thenAnswer(invocation -> keys.add(invocation.getArgument(0)));
        return redisTemplate;
    }
}
