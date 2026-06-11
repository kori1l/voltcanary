package com.voltcanary.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.voltcanary.common.security.JwtAccessTokenService;
import com.voltcanary.identity.IdentityServiceApplication;
import com.voltcanary.identity.dto.AuthTokenResponse;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.beans.factory.annotation.Autowired;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
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
                        + "org.springframework.boot.autoconfigure.security.oauth2.resource.reactive.ReactiveOAuth2ResourceServerAutoConfiguration"
        })
@AutoConfigureWebTestClient
class GatewayIdentityIntegrationTest {

    private static ConfigurableApplicationContext identityContext;

    @Autowired
    private WebTestClient webTestClient;

    @DynamicPropertySource
    static void gatewayProperties(DynamicPropertyRegistry registry) {
        startIdentityService();
        String identityBaseUrl = "http://localhost:" + identityContext.getEnvironment().getProperty("local.server.port");

        registry.add("app.jwt.key-id", () -> JwtAccessTokenService.DEV_KEY_ID);
        registry.add("app.jwt.public-keys", () -> JwtAccessTokenService.DEV_KEY_ID + "=" + JwtAccessTokenService.DEV_PUBLIC_KEY);
        registry.add("app.jwt.issuer", () -> "voltcanary-identity");
        registry.add("app.jwt.audience", () -> "voltcanary-api");
        registry.add("spring.cloud.gateway.server.webflux.routes[0].id", () -> "identity-auth");
        registry.add("spring.cloud.gateway.server.webflux.routes[0].uri", () -> identityBaseUrl);
        registry.add("spring.cloud.gateway.server.webflux.routes[0].predicates[0]", () -> "Path=/auth/**");
        registry.add("spring.cloud.gateway.server.webflux.routes[1].id", () -> "protected-test");
        registry.add("spring.cloud.gateway.server.webflux.routes[1].uri", () -> "forward:/internal/ping");
        registry.add("spring.cloud.gateway.server.webflux.routes[1].predicates[0]", () -> "Path=/protected");
    }

    @AfterAll
    static void stopIdentityService() {
        if (identityContext != null) {
            identityContext.close();
        }
    }

    @Test
    void gatewayRoutesIdentityAuthAndProtectsBackendRoutes() {
        String email = "gateway-identity-" + UUID.randomUUID() + "@example.com";
        webTestClient.post()
                .uri("/auth/register")
                .bodyValue(Map.of("email", email, "password", "Password123!"))
                .exchange()
                .expectStatus().isOk();

        AuthTokenResponse tokens = webTestClient.post()
                .uri("/auth/login")
                .bodyValue(Map.of("email", email, "password", "Password123!"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(AuthTokenResponse.class)
                .returnResult()
                .getResponseBody();
        assertThat(tokens).isNotNull();

        webTestClient.post()
                .uri("/auth/refresh")
                .bodyValue(Map.of("refreshToken", tokens.refreshToken()))
                .exchange()
                .expectStatus().isOk();

        webTestClient.get()
                .uri("/protected")
                .exchange()
                .expectStatus().isUnauthorized();

        webTestClient.get()
                .uri("/protected")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.accessToken())
                .exchange()
                .expectStatus().isOk();

        webTestClient.get()
                .uri("/protected")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + expiredAccessToken())
                .exchange()
                .expectStatus().isUnauthorized();
    }

    private static void startIdentityService() {
        if (identityContext != null) {
            return;
        }
        identityContext = new SpringApplicationBuilder(IdentityServiceApplication.class)
                .properties(
                        "spring.config.name=gateway-identity-test",
                        "server.port=0",
                        "spring.main.web-application-type=servlet",
                        "spring.autoconfigure.exclude=org.springframework.cloud.gateway.config.GatewayClassPathWarningAutoConfiguration,"
                                + "org.springframework.cloud.gateway.config.GatewayAutoConfiguration,"
                                + "org.springframework.cloud.gateway.config.GatewayMetricsAutoConfiguration,"
                                + "org.springframework.cloud.gateway.config.GatewayRedisAutoConfiguration",
                        "app.jwt.key-id=" + JwtAccessTokenService.DEV_KEY_ID,
                        "app.jwt.private-key=" + JwtAccessTokenService.DEV_PRIVATE_KEY,
                        "app.jwt.public-keys=" + JwtAccessTokenService.DEV_KEY_ID + "=" + JwtAccessTokenService.DEV_PUBLIC_KEY,
                        "app.jwt.issuer=voltcanary-identity",
                        "app.jwt.audience=voltcanary-api",
                        "spring.datasource.url=jdbc:h2:mem:gateway_identity;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
                        "spring.jpa.hibernate.ddl-auto=validate",
                        "spring.jpa.open-in-view=false",
                        "app.auth.login.max-attempts-per-email=100",
                        "app.auth.login.max-attempts-per-ip=1000")
                .run();
    }

    private String expiredAccessToken() {
        JwtAccessTokenService jwtAccessTokenService = new JwtAccessTokenService(
                "change_me",
                "voltcanary-identity",
                "voltcanary-api");
        return jwtAccessTokenService.create(
                UUID.randomUUID().toString(),
                "expired@example.com",
                "USER",
                -60);
    }
}
