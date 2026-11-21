package com.voltcanary.gateway.filter;

import com.voltcanary.gateway.error.GatewayErrorResponseWriter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
@Slf4j
public class GatewayAuthorizationGlobalFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().pathWithinApplication().value();
        if (path.startsWith("/auth/admin/") && !hasRole(exchange, "ADMIN")) {
            String correlationId = exchange.getRequest().getHeaders()
                    .getFirst(CorrelationIdGlobalFilter.CORRELATION_ID_HEADER);
            log.warn(
                    "securityEvent=authorization_rejected correlationId={} requiredRole=ADMIN path={}",
                    correlationId,
                    path);
            return GatewayErrorResponseWriter.write(
                    exchange,
                    HttpStatus.FORBIDDEN,
                    "forbidden",
                    "Insufficient permissions");
        }
        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 3;
    }

    private boolean hasRole(ServerWebExchange exchange, String requiredRole) {
        String role = exchange.getRequest().getHeaders().getFirst(JwtAuthenticationGlobalFilter.USER_ROLE_HEADER);
        return requiredRole.equals(role);
    }
}
