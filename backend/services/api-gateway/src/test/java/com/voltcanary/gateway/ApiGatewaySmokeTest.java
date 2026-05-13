package com.voltcanary.gateway;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.jwt.secret=change_me",
                "spring.cloud.gateway.server.webflux.routes[0].id=auth-test",
                "spring.cloud.gateway.server.webflux.routes[0].uri=forward:/internal/ping",
                "spring.cloud.gateway.server.webflux.routes[0].predicates[0]=Path=/auth/login",
                "spring.cloud.gateway.server.webflux.routes[1].id=devices-test",
                "spring.cloud.gateway.server.webflux.routes[1].uri=forward:/internal/ping",
                "spring.cloud.gateway.server.webflux.routes[1].predicates[0]=Path=/devices",
                "spring.cloud.gateway.server.webflux.routes[2].id=devices-correlation-test",
                "spring.cloud.gateway.server.webflux.routes[2].uri=forward:/internal/ping",
                "spring.cloud.gateway.server.webflux.routes[2].predicates[0]=Path=/devices/correlation"
        })
@AutoConfigureWebTestClient
class ApiGatewaySmokeTest {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void contextLoads() {
    }

    @Test
    void healthEndpointReturns200() {
        webTestClient.get().uri("/actuator/health").exchange().expectStatus().isOk();
    }

    @Test
    void internalPingReturns200() {
        webTestClient.get().uri("/internal/ping").exchange().expectStatus().isOk();
    }

    @Test
    void authLoginIsPublicAndGetsCorrelationId() {
        webTestClient.post()
                .uri("/auth/login")
                .exchange()
                .expectStatus().isEqualTo(405)
                .expectHeader().exists("X-Correlation-Id");
    }

    @Test
    void devicesWithoutTokenReturns401() {
        webTestClient.get().uri("/devices").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void devicesWithTokenPassesThroughAndKeepsCorrelationId() {
        webTestClient.get()
                .uri("/devices")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt())
                .header("X-Correlation-Id", "test-correlation-id")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Correlation-Id", "test-correlation-id");
    }

    @Test
    void corsAllowsFrontendOrigin() {
        webTestClient.method(HttpMethod.OPTIONS)
                .uri("/devices")
                .header(HttpHeaders.ORIGIN, "http://localhost:3000")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:3000");
    }

    private String jwt() {
        try {
            Instant now = Instant.now();
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject(UUID.randomUUID().toString())
                    .claim("email", "gateway@example.com")
                    .claim("role", "USER")
                    .issueTime(Date.from(now))
                    .expirationTime(Date.from(now.plusSeconds(900)))
                    .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(sha256("change_me")));
            return jwt.serialize();
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to create test JWT", exception);
        }
    }

    private byte[] sha256(String value) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    }
}
