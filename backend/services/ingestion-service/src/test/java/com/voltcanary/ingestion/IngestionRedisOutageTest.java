package com.voltcanary.ingestion;

import com.voltcanary.ingestion.abuse.IngestionRateLimiter;
import com.voltcanary.ingestion.canonical.IngestionContext;
import com.voltcanary.ingestion.canonical.IncomingTelemetryMessage;
import com.voltcanary.ingestion.canonical.TelemetryOrigin;
import com.voltcanary.ingestion.config.IngestionProperties;
import com.voltcanary.ingestion.source.ResolvedSource;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IngestionRedisOutageTest {

    @Test
    void rateLimiterFallsBackToInMemoryCountersWhenRedisIsUnavailable() {
        IngestionProperties properties = new IngestionProperties();
        properties.setRateLimitEnabled(true);
        properties.setRedisRateLimitEnabled(true);
        properties.setRateLimitWindow(Duration.ofMinutes(1));
        properties.setSourceRequestsPerWindow(1);
        properties.setDeviceRequestsPerWindow(10);
        properties.setAccountRequestsPerWindow(10);
        properties.setIpRequestsPerWindow(10);
        StringRedisTemplate redisTemplate = redisTemplateFailingOnIncrement();
        IngestionRateLimiter limiter = new IngestionRateLimiter(
                properties,
                Optional.of(redisTemplate),
                new SimpleMeterRegistry());
        ResolvedSource source = source();
        IncomingTelemetryMessage message = message(source.deviceId(), source.externalId());

        limiter.check(message, source);

        assertThatThrownBy(() -> limiter.check(message, source))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("429 TOO_MANY_REQUESTS");
    }

    private StringRedisTemplate redisTemplateFailingOnIncrement() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> operations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(operations);
        when(operations.increment(anyString()))
                .thenThrow(new RedisConnectionFailureException("redis unavailable"));
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
