package com.voltcanary.gateway.filter;

import com.voltcanary.gateway.config.GatewayRateLimitProperties;
import com.voltcanary.gateway.error.GatewayErrorResponseWriter;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class GatewayRateLimitGlobalFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(GatewayRateLimitGlobalFilter.class);

    private final GatewayRateLimitProperties properties;
    private final ConcurrentMap<String, WindowCounter> counters = new ConcurrentHashMap<>();
    private final ReactiveStringRedisTemplate redisTemplate;

    public GatewayRateLimitGlobalFilter(
            GatewayRateLimitProperties properties,
            Optional<ReactiveStringRedisTemplate> redisTemplate) {
        this.properties = properties;
        this.redisTemplate = redisTemplate.orElse(null);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!properties.isEnabled()) {
            return chain.filter(exchange);
        }

        Limit limit = limitFor(exchange);
        String key = limit.name + ":" + clientIp(exchange);
        long windowStartedAt = currentWindowStartedAt();
        return allow(key, windowStartedAt, limit.maxRequests)
                .flatMap(allowed -> {
                    if (!allowed) {
                        return reject(exchange, limit);
                    }
                    return chain.filter(exchange);
                });
    }

    private Mono<Boolean> allow(String key, long windowStartedAt, int limit) {
        if (properties.isRedisEnabled() && redisTemplate != null) {
            return allowRedis(key, windowStartedAt, limit)
                    .onErrorResume(exception -> {
                        log.warn("gatewayRateLimitRedisUnavailable reasonCode={}",
                                exception.getClass().getSimpleName());
                        return Mono.fromSupplier(() -> allowInMemory(key, windowStartedAt, limit));
                    });
        }
        return Mono.fromSupplier(() -> allowInMemory(key, windowStartedAt, limit));
    }

    private Mono<Boolean> allowRedis(String key, long windowStartedAt, int limit) {
        String redisKey = "gateway:rate-limit:" + key + ":" + windowStartedAt;
        return redisTemplate.opsForValue()
                .increment(redisKey)
                .flatMap(count -> {
                    Mono<Boolean> expire = count == 1L
                            ? redisTemplate.expire(redisKey, redisTtl())
                            : Mono.just(Boolean.TRUE);
                    return expire.thenReturn(count <= limit);
                });
    }

    private boolean allowInMemory(String key, long windowStartedAt, int limit) {
        WindowCounter counter = counters.compute(key, (ignored, existing) -> {
            if (existing == null || existing.windowStartedAt != windowStartedAt) {
                return new WindowCounter(windowStartedAt);
            }
            return existing;
        });
        return counter.count.incrementAndGet() <= limit;
    }

    private Mono<Void> reject(ServerWebExchange exchange, Limit limit) {
        String correlationId = exchange.getRequest().getHeaders()
                .getFirst(CorrelationIdGlobalFilter.CORRELATION_ID_HEADER);
        log.warn(
                "securityEvent=rate_limited correlationId={} category={} clientIp={} path={}",
                correlationId,
                limit.name,
                clientIp(exchange),
                exchange.getRequest().getURI().getPath());
        return GatewayErrorResponseWriter.write(
                exchange,
                HttpStatus.TOO_MANY_REQUESTS,
                "rate_limited",
                "Too many requests");
    }

    private Duration redisTtl() {
        return properties.getWindow().multipliedBy(2);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }

    private Limit limitFor(ServerWebExchange exchange) {
        String path = exchange.getRequest().getPath().pathWithinApplication().value();
        if (path.equals("/auth/login") || path.equals("/auth/register")) {
            return new Limit("auth", properties.getAuthRequestsPerWindow());
        }
        if (path.startsWith("/ingestion/")) {
            return new Limit("ingestion", properties.getIngestionRequestsPerWindow());
        }
        return new Limit("general", properties.getGeneralRequestsPerWindow());
    }

    private String clientIp(ServerWebExchange exchange) {
        String forwardedFor = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",", 2)[0].trim();
        }
        if (exchange.getRequest().getRemoteAddress() == null) {
            return "unknown";
        }
        return exchange.getRequest().getRemoteAddress().getAddress().getHostAddress();
    }

    private long currentWindowStartedAt() {
        long windowMillis = Math.max(1, properties.getWindow().toMillis());
        long now = System.currentTimeMillis();
        return now - (now % windowMillis);
    }

    private record Limit(String name, int maxRequests) {
    }

    private static final class WindowCounter {
        private final long windowStartedAt;
        private final AtomicInteger count = new AtomicInteger();

        private WindowCounter(long windowStartedAt) {
            this.windowStartedAt = windowStartedAt;
        }
    }
}
