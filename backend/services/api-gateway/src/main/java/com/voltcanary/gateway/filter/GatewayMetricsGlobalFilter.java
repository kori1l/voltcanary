package com.voltcanary.gateway.filter;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class GatewayMetricsGlobalFilter implements GlobalFilter, Ordered {

    private final MeterRegistry meterRegistry;

    public GatewayMetricsGlobalFilter(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        Timer.Sample sample = Timer.start(meterRegistry);
        return chain.filter(exchange).doFinally(signalType -> sample.stop(Timer.builder("voltcanary.gateway.requests")
                .description("Gateway requests by route, method, and status")
                .tag("routeId", routeId(exchange))
                .tag("method", String.valueOf(exchange.getRequest().getMethod()))
                .tag("status", status(exchange))
                .register(meterRegistry)));
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 1;
    }

    private String routeId(ServerWebExchange exchange) {
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        return route == null ? "unmatched" : route.getId();
    }

    private String status(ServerWebExchange exchange) {
        HttpStatusCode statusCode = exchange.getResponse().getStatusCode();
        return statusCode == null ? "UNKNOWN" : String.valueOf(statusCode.value());
    }
}
