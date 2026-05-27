package com.voltcanary.gateway.filter;

import com.voltcanary.common.security.JwtAccessTokenClaims;
import com.voltcanary.common.security.JwtAccessTokenService;
import com.voltcanary.gateway.error.GatewayErrorResponseWriter;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class JwtAuthenticationGlobalFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationGlobalFilter.class);

    public static final String USER_ID_HEADER = "X-User-Id";
    public static final String USER_EMAIL_HEADER = "X-User-Email";
    public static final String USER_ROLE_HEADER = "X-User-Role";

    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/auth/register",
            "/auth/login",
            "/auth/refresh",
            "/auth/logout",
            "/auth/forgot-password",
            "/auth/reset-password",
            "/auth/email-verification/request",
            "/auth/email-verification/confirm",
            "/actuator/health",
            "/actuator/info",
            "/internal/ping");

    private static final Set<String> PUBLIC_PATH_PREFIXES = Set.of(
            "/ingestion/readings",
            "/ingestion/imports/readings");

    private final JwtAccessTokenService accessTokenService;

    public JwtAuthenticationGlobalFilter(
            @Value("${app.jwt.key-id:${JWT_KEY_ID:dev-rsa}}") String activeKeyId,
            @Value("${app.jwt.public-keys:${JWT_PUBLIC_KEYS:}}") String publicKeys,
            @Value("${app.jwt.issuer:${JWT_ISSUER:voltcanary-identity}}") String issuer,
            @Value("${app.jwt.audience:${JWT_AUDIENCE:voltcanary-api}}") String audience) {
        this.accessTokenService = new JwtAccessTokenService(activeKeyId, publicKeys, issuer, audience);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (isPublic(exchange)) {
            return chain.filter(stripUserHeaders(exchange));
        }

        String authorization = exchange.getRequest().getHeaders().getFirst("Authorization");
        if (authorization == null || authorization.isBlank()) {
            return unauthorized(exchange, "missing_token");
        }
        if (!authorization.startsWith("Bearer ")) {
            return unauthorized(exchange, "malformed_authorization_header");
        }

        try {
            JwtAccessTokenClaims claims = accessTokenService.validate(authorization.substring("Bearer ".length()));
            String userId = claims.subject();
            String userEmail = claims.email();
            String userRole = claims.role();
            ServerWebExchange authenticatedExchange = exchange.mutate()
                    .request(request -> request.headers(headers -> {
                        headers.remove(USER_ID_HEADER);
                        headers.remove(USER_EMAIL_HEADER);
                        headers.remove(USER_ROLE_HEADER);
                        headers.set(USER_ID_HEADER, userId);
                        headers.set(USER_EMAIL_HEADER, userEmail);
                        headers.set(USER_ROLE_HEADER, userRole);
                    }))
                    .build();
            return chain.filter(authenticatedExchange);
        } catch (Exception exception) {
            return unauthorized(exchange, authFailureReason(exception));
        }
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 2;
    }

    private boolean isPublic(ServerWebExchange exchange) {
        if (exchange.getRequest().getMethod() == HttpMethod.OPTIONS) {
            return true;
        }
        String path = exchange.getRequest().getPath().pathWithinApplication().value();
        return PUBLIC_PATHS.contains(path)
                || PUBLIC_PATH_PREFIXES.stream().anyMatch(path::startsWith);
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange, String reason) {
        String correlationId = exchange.getRequest().getHeaders()
                .getFirst(CorrelationIdGlobalFilter.CORRELATION_ID_HEADER);
        log.warn(
                "securityEvent=authentication_rejected correlationId={} reason={} path={}",
                correlationId,
                reason,
                exchange.getRequest().getURI().getPath());
        return GatewayErrorResponseWriter.write(
                exchange,
                HttpStatus.UNAUTHORIZED,
                "unauthorized",
                "Authentication is required");
    }

    private String authFailureReason(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return "invalid_token";
        }
        String normalized = message.toLowerCase();
        if (normalized.contains("expired")) {
            return "expired_token";
        }
        if (normalized.contains("signature")) {
            return "invalid_signature";
        }
        if (normalized.contains("parse") || normalized.contains("invalid access token")) {
            return "malformed_token";
        }
        return "invalid_token";
    }

    private ServerWebExchange stripUserHeaders(ServerWebExchange exchange) {
        return exchange.mutate()
                .request(request -> request.headers(headers -> {
                    headers.remove(USER_ID_HEADER);
                    headers.remove(USER_EMAIL_HEADER);
                    headers.remove(USER_ROLE_HEADER);
                }))
                .build();
    }
}
