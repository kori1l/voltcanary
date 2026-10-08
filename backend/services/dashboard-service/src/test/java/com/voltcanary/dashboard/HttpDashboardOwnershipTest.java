package com.voltcanary.dashboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.voltcanary.common.security.JwtAccessTokenService;
import com.voltcanary.dashboard.adapter.HttpDashboardOwnership;
import com.voltcanary.dashboard.config.DashboardHttpProperties;
import com.voltcanary.dashboard.config.DashboardJwtProperties;
import com.voltcanary.dashboard.domain.DashboardFailure;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpDashboardOwnershipTest {
    private final UUID user = UUID.randomUUID();
    private final UUID account = UUID.randomUUID();
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicInteger identityReads = new AtomicInteger();
    private final AtomicInteger accountBootstrapReads = new AtomicInteger();
    private volatile String userStatus = "ACTIVE";
    private volatile String accountStatus = "ACTIVE";
    private volatile UUID accountUser;
    private HttpServer server;
    private HttpDashboardOwnership ownership;
    private String authorization;

    @BeforeEach
    void start() throws Exception {
        accountUser = user;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String body;
            if (path.equals("/auth/me")) {
                identityReads.incrementAndGet();
                body = "{\"id\":\"" + user + "\",\"role\":\"ADMIN\",\"status\":\"" + userStatus + "\"}";
            } else if (path.equals("/accounts/current")) {
                accountBootstrapReads.incrementAndGet();
                body = "{\"id\":\"" + account + "\",\"userId\":\"" + accountUser + "\",\"status\":\"" + accountStatus + "\"}";
            } else {
                body = "{}";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        ownership = new HttpDashboardOwnership(new DashboardHttpProperties(base, base, Duration.ofSeconds(1)),
                new DashboardJwtProperties("dev-rsa", "", "voltcanary-identity", "voltcanary-api"), new ObjectMapper());
        authorization = "Bearer " + new JwtAccessTokenService("test", "voltcanary-identity", "voltcanary-api")
                .create(user.toString(), "fixture@example.invalid", "ADMIN", 60);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void resolvesSignedActorAndRechecksCurrentLifecycleEveryTime() {
        assertThat(ownership.resolve(authorization).accountId()).isEqualTo(account);
        assertThat(ownership.resolve(authorization).userId()).isEqualTo(user);
        userStatus = "DISABLED";
        assertFailure("ACTOR_INACTIVE", () -> ownership.resolve(authorization));
        assertThat(identityReads.get()).isEqualTo(3);
    }

    @Test
    void inactiveOrMismatchedAccountNeverGrantsOwnership() {
        accountStatus = "DELETED";
        assertFailure("ACTOR_INACTIVE", () -> ownership.resolve(authorization));
        accountStatus = "ACTIVE";
        accountUser = UUID.randomUUID();
        assertFailure("ACTOR_INACTIVE", () -> ownership.resolve(authorization));
    }

    @Test
    void unavailableDependencyDoesNotServeCachedAuthorization() {
        ownership.resolve(authorization);
        status.set(503);
        assertFailure("OWNERSHIP_UNAVAILABLE", () -> ownership.resolve(authorization));
        status.set(401);
        assertFailure("UNAUTHENTICATED", () -> ownership.resolve(authorization));
    }

    @Test
    void invalidTokensFailBeforeConsultingOwnershipServices() {
        assertFailure("UNAUTHENTICATED", () -> ownership.resolve("Bearer malformed"));
        assertFailure("UNAUTHENTICATED", () -> ownership.resolve(null));
        assertThat(identityReads.get()).isZero();
    }

    @Test
    void queryActorUsesReadOnlyActiveScopeAndNeverBootstrapsAccounts() {
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        var reads = org.mockito.Mockito.mock(com.voltcanary.dashboard.port.DashboardEvidenceReads.class);
        org.mockito.Mockito.when(reads.activeActor(authorization, user))
                .thenReturn(new com.voltcanary.dashboard.domain.DashboardActor(user, account));
        var readOnly = new HttpDashboardOwnership(new DashboardHttpProperties(base, base, Duration.ofSeconds(1)),
                new DashboardJwtProperties("dev-rsa", "", "voltcanary-identity", "voltcanary-api"), new ObjectMapper(), reads);
        assertThat(readOnly.resolveReadOnly(authorization).accountId()).isEqualTo(account);
        assertThat(accountBootstrapReads.get()).isZero();
        userStatus = "DISABLED";
        assertFailure("ACTOR_INACTIVE", () -> readOnly.resolveReadOnly(authorization));
        org.mockito.Mockito.verify(reads, org.mockito.Mockito.times(1)).activeActor(authorization, user);
    }

    private void assertFailure(String code, Runnable operation) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(DashboardFailure.class,
                failure -> assertThat(failure.code()).isEqualTo(code));
    }
}
