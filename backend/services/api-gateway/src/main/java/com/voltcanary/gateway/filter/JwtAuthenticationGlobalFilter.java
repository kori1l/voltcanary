package com.voltcanary.gateway.filter;

import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Date;
import java.util.Set;
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

    public static final String USER_ID_HEADER = "X-User-Id";
    public static final String USER_EMAIL_HEADER = "X-User-Email";
    public static final String USER_ROLE_HEADER = "X-User-Role";

    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/auth/register",
            "/auth/login",
            "/actuator/health",
            "/actuator/info",
            "/internal/ping");

    private final byte[] signingKey;

    public JwtAuthenticationGlobalFilter(@Value("${app.jwt.secret:${JWT_SECRET:change_me}}") String secret) {
        this.signingKey = sha256(secret);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (isPublic(exchange)) {
            return chain.filter(exchange);
        }

        String authorization = exchange.getRequest().getHeaders().getFirst("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return unauthorized(exchange);
        }

        try {
            JWTClaimsSet claims = parseAndValidate(authorization.substring("Bearer ".length()));
            String userId = claims.getSubject();
            String userEmail = claims.getStringClaim("email");
            String userRole = claims.getStringClaim("role");
            ServerWebExchange authenticatedExchange = exchange.mutate()
                    .request(request -> request.headers(headers -> {
                        headers.set(USER_ID_HEADER, userId);
                        headers.set(USER_EMAIL_HEADER, userEmail);
                        headers.set(USER_ROLE_HEADER, userRole);
                    }))
                    .build();
            return chain.filter(authenticatedExchange);
        } catch (Exception exception) {
            return unauthorized(exchange);
        }
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }

    private boolean isPublic(ServerWebExchange exchange) {
        if (exchange.getRequest().getMethod() == HttpMethod.OPTIONS) {
            return true;
        }
        return PUBLIC_PATHS.contains(exchange.getRequest().getPath().pathWithinApplication().value());
    }

    private JWTClaimsSet parseAndValidate(String token) throws Exception {
        SignedJWT jwt = SignedJWT.parse(token);
        JWSVerifier verifier = new MACVerifier(signingKey);
        if (!jwt.verify(verifier)) {
            throw new IllegalArgumentException("Invalid token signature");
        }

        JWTClaimsSet claims = jwt.getJWTClaimsSet();
        Date expiresAt = claims.getExpirationTime();
        if (expiresAt == null || expiresAt.before(new Date())) {
            throw new IllegalArgumentException("Access token expired");
        }
        return claims;
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        return exchange.getResponse().setComplete();
    }

    private byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to initialize JWT signing key", exception);
        }
    }
}
