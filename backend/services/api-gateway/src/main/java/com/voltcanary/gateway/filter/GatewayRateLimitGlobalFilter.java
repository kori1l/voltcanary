package com.voltcanary.gateway.filter;

import com.voltcanary.common.security.BoundedWindowRateLimiter;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import com.voltcanary.gateway.config.GatewayRateLimitProperties;
import com.voltcanary.gateway.error.GatewayErrorResponseWriter;
import java.time.Duration;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
@Slf4j
public class GatewayRateLimitGlobalFilter implements GlobalFilter, Ordered {

    private static final RedisScript<Long> COUNT_SCRIPT =
            new DefaultRedisScript<>(
                    "local n=redis.call('INCR',KEYS[1]); if n==1 or redis.call('PTTL',KEYS[1])<0 then redis.call('PEXPIRE',KEYS[1],ARGV[1]); end; return n;", Long.class);

    private final GatewayRateLimitProperties properties;
    private final BoundedWindowRateLimiter counters =
            new BoundedWindowRateLimiter(10_000);
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
        return redisTemplate.execute(COUNT_SCRIPT, List.of(redisKey), List.of(Long.toString(redisTtl().toMillis())))
                .next().map(count -> count <= limit).defaultIfEmpty(false);
    }

    private boolean allowInMemory(String key, long windowStartedAt, int limit) {
        return counters.allow(key, windowStartedAt, limit);
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
        var remote = exchange.getRequest().getRemoteAddress();
        if (remote == null || remote.getAddress() == null) return "unknown";
        String peer = remote.getAddress().getHostAddress();
        var trusted = properties.getTrustedProxyAddresses().stream().map(this::numericAddress)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        if (!trusted.contains(peer)) return peer;
        String forwarded = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (forwarded == null || forwarded.length() > 1024) return peer;
        String[] chain = forwarded.split(",", -1);
        if (chain.length > 16) return peer;
        for (int index = chain.length - 1; index >= 0; index--) {
            String candidate = numericAddress(chain[index].trim());
            if (candidate == null) return peer;
            peer = candidate;
            if (!trusted.contains(peer)) break;
        }
        return peer;
    }

    private String numericAddress(String value) {
        if (value == null || !value.matches("[0-9a-fA-F:.]+") || !(value.contains(".") || value.contains(":"))) return null;
        try { return InetAddress.getByName(value).getHostAddress(); }
        catch (UnknownHostException invalid) { return null; }
    }

    private long currentWindowStartedAt() {
        long windowMillis = Math.max(1, properties.getWindow().toMillis());
        long now = System.currentTimeMillis();
        return now - (now % windowMillis);
    }

    private record Limit(String name, int maxRequests) {
    }

}
