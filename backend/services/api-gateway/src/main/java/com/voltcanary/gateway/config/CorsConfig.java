package com.voltcanary.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Mono;

@Configuration
public class CorsConfig {

    private static final String FRONTEND_ORIGIN = "http://localhost:3000";

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    WebFilter frontendCorsFilter() {
        return (exchange, chain) -> {
            String origin = exchange.getRequest().getHeaders().getOrigin();
            if (FRONTEND_ORIGIN.equals(origin)) {
                HttpHeaders headers = exchange.getResponse().getHeaders();
                headers.setAccessControlAllowOrigin(FRONTEND_ORIGIN);
                headers.setAccessControlAllowCredentials(true);
                headers.add(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, "Authorization, Content-Type, X-Correlation-Id");
                headers.add(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, "GET, POST, PATCH, PUT, DELETE, OPTIONS");
                headers.add(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, "X-Correlation-Id");
            }

            if (exchange.getRequest().getMethod() == HttpMethod.OPTIONS) {
                exchange.getResponse().setStatusCode(HttpStatus.OK);
                return exchange.getResponse().setComplete();
            }

            return chain.filter(exchange);
        };
    }
}
