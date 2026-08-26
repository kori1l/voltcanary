package com.voltcanary.ingestion;

import com.voltcanary.ingestion.abuse.IngestionRateLimiter;
import com.voltcanary.ingestion.canonical.IngestionContext;
import com.voltcanary.ingestion.canonical.IncomingTelemetryMessage;
import com.voltcanary.ingestion.canonical.TelemetryOrigin;
import com.voltcanary.ingestion.config.IngestionProperties;
import com.voltcanary.ingestion.source.ResolvedSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IngestionSharedRateLimitTest {

    @Test
    void rateLimitIsCountedAcrossServiceInstances() {
        IngestionProperties properties = new IngestionProperties();
        properties.setRateLimitWindow(Duration.ofMinutes(1));
        properties.setSourceRequestsPerWindow(1);
        properties.setDeviceRequestsPerWindow(10);
        properties.setAccountRequestsPerWindow(10);
        properties.setIpRequestsPerWindow(10);
        StringRedisTemplate redisTemplate = redisTemplateWithSharedCounters();
        IngestionRateLimiter firstReplica = new IngestionRateLimiter(
                properties,
                Optional.of(redisTemplate),
                new SimpleMeterRegistry());
        IngestionRateLimiter secondReplica = new IngestionRateLimiter(
                properties,
                Optional.of(redisTemplate),
                new SimpleMeterRegistry());
        ResolvedSource source = source();
        IncomingTelemetryMessage message = message(source.deviceId(), source.externalId());

        firstReplica.check(message, source);

        assertThatThrownBy(() -> secondReplica.check(message, source))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("429 TOO_MANY_REQUESTS");
    }

    private StringRedisTemplate redisTemplateWithSharedCounters() {
        ConcurrentMap<String, Long> counters = new ConcurrentHashMap<>();
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> operations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(operations);
        when(operations.increment(anyString())).thenAnswer(invocation -> counters.merge(invocation.getArgument(0), 1L, Long::sum));
        when(redisTemplate.expire(anyString(), any())).thenReturn(true);
        return redisTemplate;
    }

    private IncomingTelemetryMessage message(UUID deviceId, String sourceId) {
        return new IncomingTelemetryMessage(
                sourceId,
                deviceId,
                null,
                Instant.now(),
                Instant.now(),
                Map.of("activePowerW", 840.2),
                TelemetryOrigin.httpPush(),
                new IngestionContext("correlation-1", "token", "127.0.0.1", Map.of()),
                false);
    }

    private ResolvedSource source() {
        return new ResolvedSource(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "plug-001",
                "SIMULATOR",
                "REST",
                "ACTIVE");
    }
}
