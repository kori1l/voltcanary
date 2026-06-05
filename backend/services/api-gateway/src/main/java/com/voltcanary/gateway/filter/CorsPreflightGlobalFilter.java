package com.voltcanary.gateway.filter;

import com.voltcanary.gateway.config.GatewayCorsProperties;
import java.util.List;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.PatternMatchUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class CorsPreflightGlobalFilter implements GlobalFilter, Ordered {

    private final GatewayCorsProperties properties;

    public CorsPreflightGlobalFilter(GatewayCorsProperties properties) {
        this.properties = properties;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String origin = exchange.getRequest().getHeaders().getFirst(HttpHeaders.ORIGIN);
        String requestedMethod = exchange.getRequest().getHeaders().getFirst(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD);
        if (exchange.getRequest().getMethod() != HttpMethod.OPTIONS || origin == null || requestedMethod == null) {
            return chain.filter(exchange);
        }
        if (!isAllowedOrigin(origin)) {
            exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
            return exchange.getResponse().setComplete();
        }

        HttpHeaders headers = exchange.getResponse().getHeaders();
        headers.set(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origin);
        headers.set(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, String.valueOf(properties.isAllowCredentials()));
        headers.set(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, String.join(",", properties.getAllowedMethods()));
        headers.set(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, String.join(",", properties.getAllowedHeaders()));
        headers.set(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, String.join(",", properties.getExposedHeaders()));
        exchange.getResponse().setStatusCode(HttpStatus.OK);
        return exchange.getResponse().setComplete();
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE - 1;
    }

    private boolean isAllowedOrigin(String origin) {
        List<String> patterns = properties.getAllowedOriginPatterns();
        return patterns.stream().anyMatch(pattern -> "*".equals(pattern) || PatternMatchUtils.simpleMatch(pattern, origin));
    }
}
