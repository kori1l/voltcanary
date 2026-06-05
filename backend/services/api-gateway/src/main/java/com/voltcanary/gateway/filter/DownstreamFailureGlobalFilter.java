package com.voltcanary.gateway.filter;

import com.voltcanary.gateway.error.GatewayErrorResponseWriter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class DownstreamFailureGlobalFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(DownstreamFailureGlobalFilter.class);

    private final MeterRegistry meterRegistry;

    public DownstreamFailureGlobalFilter(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return chain.filter(exchange).onErrorResume(exception -> {
            if (exception instanceof ResponseStatusException) {
                return Mono.error(exception);
            }
            if (exchange.getResponse().isCommitted()) {
                return Mono.error(exception);
            }

            String correlationId = exchange.getRequest().getHeaders()
                    .getFirst(CorrelationIdGlobalFilter.CORRELATION_ID_HEADER);
            log.warn(
                    "downstreamFailure correlationId={} path={} reason={}",
                    correlationId,
                    exchange.getRequest().getURI().getPath(),
                    exception.getClass().getSimpleName());
            if (meterRegistry != null) {
                meterRegistry.counter(
                        "voltcanary.gateway.downstream.failures",
                        "path",
                        exchange.getRequest().getURI().getPath(),
                        "reason",
                        exception.getClass().getSimpleName()).increment();
            }

            return GatewayErrorResponseWriter.write(
                    exchange,
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "downstream_unavailable",
                    "Downstream service is unavailable");
        });
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

}
