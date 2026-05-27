package com.voltcanary.gateway.filter;

import com.voltcanary.gateway.config.GatewayRequestSizeProperties;
import com.voltcanary.gateway.error.GatewayErrorResponseWriter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestSizeLimitWebFilter implements WebFilter {

    private final GatewayRequestSizeProperties properties;

    public RequestSizeLimitWebFilter(GatewayRequestSizeProperties properties) {
        this.properties = properties;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        long contentLength = exchange.getRequest().getHeaders().getContentLength();
        if (contentLength >= 0 && contentLength > maxBytes(exchange)) {
            return GatewayErrorResponseWriter.write(
                    exchange,
                    HttpStatus.PAYLOAD_TOO_LARGE,
                    "payload_too_large",
                    "Request body is too large");
        }
        return chain.filter(exchange);
    }

    private long maxBytes(ServerWebExchange exchange) {
        String path = exchange.getRequest().getPath().pathWithinApplication().value();
        if (path.startsWith("/auth/")) {
            return properties.getAuthMaxBytes();
        }
        if (path.startsWith("/ingestion/")) {
            return properties.getIngestionMaxBytes();
        }
        return properties.getDefaultMaxBytes();
    }
}
