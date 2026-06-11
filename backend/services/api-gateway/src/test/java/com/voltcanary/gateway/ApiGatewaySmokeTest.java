package com.voltcanary.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.voltcanary.common.security.JwtAccessTokenService;
import com.voltcanary.gateway.config.GatewayCorsProperties;
import com.voltcanary.gateway.config.GatewayJwtProperties;
import com.voltcanary.gateway.config.GatewayRateLimitProperties;
import com.voltcanary.gateway.filter.CorsPreflightGlobalFilter;
import com.voltcanary.gateway.filter.DownstreamFailureGlobalFilter;
import com.voltcanary.gateway.filter.GatewayAuthorizationGlobalFilter;
import com.voltcanary.gateway.filter.GatewayRateLimitGlobalFilter;
import com.voltcanary.gateway.filter.JwtAuthenticationGlobalFilter;
import java.util.UUID;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.jwt.key-id=dev-rsa",
                "app.jwt.public-keys=dev-rsa=" + JwtAccessTokenService.DEV_PUBLIC_KEY,
                "app.gateway.rate-limit.redis-enabled=false",
                "management.health.redis.enabled=false",
                "spring.main.web-application-type=reactive",
                "spring.autoconfigure.exclude=org.springframework.cloud.gateway.config.GatewayClassPathWarningAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration,"
                        + "org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.security.reactive.ReactiveSecurityAutoConfiguration,"
                        + "org.springframework.boot.actuate.autoconfigure.security.reactive.ReactiveManagementWebSecurityAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.security.reactive.ReactiveUserDetailsServiceAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.security.oauth2.client.reactive.ReactiveOAuth2ClientAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.security.oauth2.client.reactive.ReactiveOAuth2ClientWebSecurityAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.security.oauth2.resource.reactive.ReactiveOAuth2ResourceServerAutoConfiguration",
                "spring.cloud.gateway.server.webflux.routes[0].id=auth-test",
                "spring.cloud.gateway.server.webflux.routes[0].uri=forward:/internal/ping",
                "spring.cloud.gateway.server.webflux.routes[0].predicates[0]=Path=/auth/login",
                "spring.cloud.gateway.server.webflux.routes[1].id=auth-register-test",
                "spring.cloud.gateway.server.webflux.routes[1].uri=forward:/internal/ping",
                "spring.cloud.gateway.server.webflux.routes[1].predicates[0]=Path=/auth/register",
                "spring.cloud.gateway.server.webflux.routes[2].id=auth-refresh-test",
                "spring.cloud.gateway.server.webflux.routes[2].uri=forward:/internal/ping",
                "spring.cloud.gateway.server.webflux.routes[2].predicates[0]=Path=/auth/refresh",
                "spring.cloud.gateway.server.webflux.routes[3].id=auth-reset-test",
                "spring.cloud.gateway.server.webflux.routes[3].uri=forward:/internal/ping",
                "spring.cloud.gateway.server.webflux.routes[3].predicates[0]=Path=/auth/reset-password",
                "spring.cloud.gateway.server.webflux.routes[4].id=auth-email-verification-test",
                "spring.cloud.gateway.server.webflux.routes[4].uri=forward:/internal/ping",
                "spring.cloud.gateway.server.webflux.routes[4].predicates[0]=Path=/auth/email-verification/confirm",
                "spring.cloud.gateway.server.webflux.routes[5].id=devices-test",
                "spring.cloud.gateway.server.webflux.routes[5].uri=forward:/internal/ping",
                "spring.cloud.gateway.server.webflux.routes[5].predicates[0]=Path=/devices",
                "spring.cloud.gateway.server.webflux.routes[6].id=devices-correlation-test",
                "spring.cloud.gateway.server.webflux.routes[6].uri=forward:/internal/ping",
                "spring.cloud.gateway.server.webflux.routes[6].predicates[0]=Path=/devices/correlation",
                "spring.cloud.gateway.server.webflux.routes[7].id=ingestion-readings-test",
                "spring.cloud.gateway.server.webflux.routes[7].uri=forward:/internal/ping",
                "spring.cloud.gateway.server.webflux.routes[7].predicates[0]=Path=/ingestion/readings"
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
    void authRegisterIsPublicAndGetsCorrelationId() {
        webTestClient.post()
                .uri("/auth/register")
                .exchange()
                .expectStatus().isEqualTo(405)
                .expectHeader().exists("X-Correlation-Id");
    }

    @Test
    void authRefreshIsPublicAndGetsCorrelationId() {
        webTestClient.post()
                .uri("/auth/refresh")
                .exchange()
                .expectStatus().isEqualTo(405)
                .expectHeader().exists("X-Correlation-Id");
    }

    @Test
    void authRecoveryEndpointsArePublic() {
        webTestClient.post()
                .uri("/auth/reset-password")
                .exchange()
                .expectStatus().isEqualTo(405);
        webTestClient.post()
                .uri("/auth/email-verification/confirm")
                .exchange()
                .expectStatus().isEqualTo(405);
    }

    @Test
    void devicesWithoutTokenReturns401() {
        webTestClient.get()
                .uri("/devices")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody(String.class)
                .value(body -> assertThat(body).contains("\"code\":\"unauthorized\""));
    }

    @Test
    void devicesWithMalformedTokenReturns401() {
        webTestClient.get()
                .uri("/devices")
                .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void devicesWithExpiredTokenReturns401() {
        webTestClient.get()
                .uri("/devices")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt(UUID.randomUUID().toString(), "gateway@example.com", "USER", -60))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void devicesWithMissingSubjectTokenReturns401() {
        webTestClient.get()
                .uri("/devices")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt(null, "gateway@example.com", "USER"))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void devicesWithMissingEmailTokenReturns401() {
        webTestClient.get()
                .uri("/devices")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt(UUID.randomUUID().toString(), null, "USER"))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void devicesWithMissingRoleTokenReturns401() {
        webTestClient.get()
                .uri("/devices")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt(UUID.randomUUID().toString(), "gateway@example.com", null))
                .exchange()
                .expectStatus().isUnauthorized();
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
    void authenticatedRequestOverwritesSpoofedUserHeaders() {
        GatewayJwtProperties jwtProperties = new GatewayJwtProperties();
        jwtProperties.setKeyId(JwtAccessTokenService.DEV_KEY_ID);
        jwtProperties.setPublicKeys(JwtAccessTokenService.DEV_KEY_ID + "=" + JwtAccessTokenService.DEV_PUBLIC_KEY);
        jwtProperties.setIssuer("voltcanary-identity");
        jwtProperties.setAudience("voltcanary-api");
        JwtAuthenticationGlobalFilter filter = new JwtAuthenticationGlobalFilter(jwtProperties);
        AtomicReference<ServerWebExchange> forwardedExchange = new AtomicReference<>();
        GatewayFilterChain chain = exchange -> {
            forwardedExchange.set(exchange);
            return Mono.empty();
        };

        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/devices")
                .header(HttpHeaders.AUTHORIZATION, "Bearer "
                        + jwt("11111111-1111-1111-1111-111111111111", "actual@example.com", "ADMIN"))
                .header(JwtAuthenticationGlobalFilter.USER_ID_HEADER, "spoofed-user")
                .header(JwtAuthenticationGlobalFilter.USER_EMAIL_HEADER, "spoofed@example.com")
                .header(JwtAuthenticationGlobalFilter.USER_ROLE_HEADER, "SPOOFED"));

        filter.filter(exchange, chain).block();

        HttpHeaders headers = forwardedExchange.get().getRequest().getHeaders();
        assertThat(headers.getFirst(JwtAuthenticationGlobalFilter.USER_ID_HEADER))
                .isEqualTo("11111111-1111-1111-1111-111111111111");
        assertThat(headers.getFirst(JwtAuthenticationGlobalFilter.USER_EMAIL_HEADER)).isEqualTo("actual@example.com");
        assertThat(headers.getFirst(JwtAuthenticationGlobalFilter.USER_ROLE_HEADER)).isEqualTo("ADMIN");
    }

    @Test
    void ingestionReadingsAcceptCollectorBearerTokenWithoutUserJwt() {
        webTestClient.post()
                .uri("/ingestion/readings")
                .header(HttpHeaders.AUTHORIZATION, "Bearer vc_src_live_test")
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .bodyValue("{\"deviceId\":\"11111111-1111-1111-1111-111111111111\",\"sourceId\":\"plug-001\",\"metrics\":{\"activePowerW\":1}}")
                .exchange()
                .expectStatus().isEqualTo(405);
    }

    @Test
    void corsAllowsFrontendOrigin() {
        CorsPreflightGlobalFilter filter = new CorsPreflightGlobalFilter(new GatewayCorsProperties());
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.options("/devices")
                .header(HttpHeaders.ORIGIN, "http://localhost:3000")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"));
        GatewayFilterChain chain = request -> Mono.empty();

        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(exchange.getResponse().getHeaders().getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
                .isEqualTo("http://localhost:3000");
    }

    @Test
    void oversizedAuthRequestReturns413() {
        webTestClient.post()
                .uri("/auth/login")
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .header(HttpHeaders.CONTENT_LENGTH, "70000")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE)
                .expectBody(String.class)
                .value(body -> assertThat(body).contains("\"code\":\"payload_too_large\""));
    }

    @Test
    void rateLimitReturns429AfterConfiguredLimit() {
        GatewayRateLimitProperties properties = new GatewayRateLimitProperties();
        properties.setAuthRequestsPerWindow(1);
        GatewayRateLimitGlobalFilter filter = new GatewayRateLimitGlobalFilter(properties, Optional.empty());
        GatewayFilterChain chain = exchange -> Mono.empty();

        MockServerWebExchange first = MockServerWebExchange.from(MockServerHttpRequest.post("/auth/login")
                .remoteAddress(new java.net.InetSocketAddress("127.0.0.1", 12345)));
        MockServerWebExchange second = MockServerWebExchange.from(MockServerHttpRequest.post("/auth/login")
                .remoteAddress(new java.net.InetSocketAddress("127.0.0.1", 12345)));

        filter.filter(first, chain).block();
        filter.filter(second, chain).block();

        assertThat(second.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(second.getResponse().getBodyAsString().block()).contains("\"code\":\"rate_limited\"");
    }

    @Test
    void adminRouteRequiresAdminRole() {
        GatewayAuthorizationGlobalFilter filter = new GatewayAuthorizationGlobalFilter();
        MockServerWebExchange userExchange = MockServerWebExchange.from(MockServerHttpRequest.get("/auth/admin/users")
                .header(JwtAuthenticationGlobalFilter.USER_ROLE_HEADER, "USER")
                .header("X-Correlation-Id", "admin-correlation-id"));

        filter.filter(userExchange, exchange -> Mono.empty()).block();

        assertThat(userExchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(userExchange.getResponse().getBodyAsString().block()).contains("\"code\":\"forbidden\"");
    }

    @Test
    void metricsEndpointExposesGatewayRequestMetric() {
        webTestClient.get().uri("/internal/ping").exchange().expectStatus().isOk();

        webTestClient.get()
                .uri("/actuator/metrics/voltcanary.gateway.requests")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(body -> assertThat(body).contains("voltcanary.gateway.requests"));
    }

    @Test
    void downstreamFailureReturns503Json() {
        DownstreamFailureGlobalFilter filter = new DownstreamFailureGlobalFilter(null);
        GatewayFilterChain failingChain = exchange -> Mono.error(new IllegalStateException("downstream failed"));
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/devices")
                .header("X-Correlation-Id", "failure-correlation-id"));

        filter.filter(exchange, failingChain).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(exchange.getResponse().getBodyAsString().block()).contains("downstream_unavailable");
    }

    private String jwt() {
        return jwt(UUID.randomUUID().toString(), "gateway@example.com", "USER");
    }

    private String jwt(String subject, String email, String role) {
        return jwt(subject, email, role, 900);
    }

    private String jwt(String subject, String email, String role, long ttlSeconds) {
        return new JwtAccessTokenService("change_me", "voltcanary-identity", "voltcanary-api")
                .create(subject, email, role, ttlSeconds);
    }
}
