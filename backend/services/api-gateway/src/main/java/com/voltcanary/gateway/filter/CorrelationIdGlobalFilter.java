package com.voltcanary.gateway.filter;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class CorrelationIdGlobalFilter implements GlobalFilter, Ordered {

    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    private static final Logger log = LoggerFactory.getLogger(CorrelationIdGlobalFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String correlationId = getOrCreateCorrelationId(exchange);
        long startedAt = System.currentTimeMillis();

        ServerWebExchange mutatedExchange = exchange.mutate()
                .request(request -> request.header(CORRELATION_ID_HEADER, correlationId))
                .build();

        mutatedExchange.getResponse().beforeCommit(() -> {
            mutatedExchange.getResponse().getHeaders().set(CORRELATION_ID_HEADER, correlationId);
            return Mono.empty();
        });

        return chain.filter(mutatedExchange)
                .doFinally(signalType -> logRequest(mutatedExchange, correlationId, startedAt));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    private String getOrCreateCorrelationId(ServerWebExchange exchange) {
        String existingCorrelationId = exchange.getRequest().getHeaders().getFirst(CORRELATION_ID_HEADER);
        if (existingCorrelationId == null || existingCorrelationId.isBlank()) {
            return UUID.randomUUID().toString();
        }
        return existingCorrelationId;
    }

    private void logRequest(ServerWebExchange exchange, String correlationId, long startedAt) {
        HttpStatusCode statusCode = exchange.getResponse().getStatusCode();
        long durationMs = System.currentTimeMillis() - startedAt;

        log.info(
                "correlationId={} method={} path={} status={} durationMs={}",
                correlationId,
                exchange.getRequest().getMethod(),
                exchange.getRequest().getURI().getPath(),
                statusCode == null ? "UNKNOWN" : statusCode.value(),
                durationMs);
    }
}
