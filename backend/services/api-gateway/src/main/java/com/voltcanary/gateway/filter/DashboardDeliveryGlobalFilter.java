package com.voltcanary.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class DashboardDeliveryGlobalFilter implements GlobalFilter, Ordered {
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (exchange.getRequest().getPath().value().startsWith("/dashboards/")) {
            exchange.getResponse().beforeCommit(() -> {
                var headers=exchange.getResponse().getHeaders();
                headers.setCacheControl("no-store");
                return Mono.empty();
            });
        }
        return chain.filter(exchange);
    }
    @Override
    public int getOrder() { return -2; }
}
