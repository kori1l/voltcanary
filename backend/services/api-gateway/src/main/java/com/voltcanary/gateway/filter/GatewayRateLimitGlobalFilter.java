package com.voltcanary.gateway.filter;

import com.voltcanary.gateway.config.GatewayRateLimitProperties;
import com.voltcanary.gateway.error.GatewayErrorResponseWriter;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class GatewayRateLimitGlobalFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(GatewayRateLimitGlobalFilter.class);

    private final GatewayRateLimitProperties properties;
    private final ConcurrentMap<String, WindowCounter> counters = new ConcurrentHashMap<>();

    public GatewayRateLimitGlobalFilter(GatewayRateLimitProperties properties) {
        this.properties = properties;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!properties.isEnabled()) {
            return chain.filter(exchange);
        }

        Limit limit = limitFor(exchange);
        String key = limit.name + ":" + clientIp(exchange);
        long windowStartedAt = currentWindowStartedAt();
        WindowCounter counter = counters.compute(key, (ignored, existing) -> {
            if (existing == null || existing.windowStartedAt != windowStartedAt) {
                return new WindowCounter(windowStartedAt);
            }
            return existing;
        });

        if (counter.count.incrementAndGet() > limit.maxRequests) {
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

        return chain.filter(exchange);
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
