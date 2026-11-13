package com.voltcanary.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.voltcanary.common.security.BearerUserIdentity;
import com.voltcanary.common.security.JwtAccessTokenService;
import com.voltcanary.common.test.SignedUserTokens;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class UserIdentityFilterTest {
    private final UserIdentityFilter filter = new UserIdentityFilter(new BearerUserIdentity(new JwtAccessTokenService(
            SignedUserTokens.KEY_ID, SignedUserTokens.PUBLIC_KEYS, "voltcanary-identity", "voltcanary-api")));

    @Test
    void everyUserSurfaceRejectsHeadersAndServiceOrCollectorCredentials() throws Exception {
        for (String root : new String[]{"telemetry", "analytics", "alerts", "alert-rules", "simulation"}) {
            for (String credential : new String[]{"", "Bearer invalid", "Bearer collector-token"}) {
                var request = new MockHttpServletRequest("POST", "/" + root + "/resource");
                request.addHeader("Authorization", credential);
                request.addHeader("X-User-Id", UUID.randomUUID().toString());
                request.addHeader("X-User-Role", "ADMIN");
                request.addHeader("X-Internal-Service-Token", "dev-internal-token");
                var response = new MockHttpServletResponse();
                AtomicBoolean called = new AtomicBoolean();
                filter.doFilter(request, response, (req, res) -> called.set(true));
                assertThat(response.getStatus()).isEqualTo(401);
                assertThat(called).isFalse();
            }
        }
    }

    @Test
    void verifiedActorWinsAcrossAllHeaderAccessMethodsAndAccountHintsAreRemoved() throws Exception {
        UUID actor = UUID.randomUUID();
        var request = new MockHttpServletRequest("GET", "/alerts");
        request.addHeader("Authorization", "Bearer " + SignedUserTokens.create(actor, "actor@example.invalid", "USER"));
        request.addHeader("X-User-Id", UUID.randomUUID().toString());
        request.addHeader("X-User-Role", "ADMIN");
        request.addHeader("X-Account-Id", UUID.randomUUID().toString());
        request.addHeader("X-Workspace-Role", "OWNER");
        request.addHeader("X-Capabilities", "*");
        request.addHeader("X-Internal-Service-Token", "dev-internal-token");
        request.addHeader("X-Correlation-Id", "trace");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> {
            var verified = (HttpServletRequest) req;
            assertThat(verified.getHeader("X-User-Id")).isEqualTo(actor.toString());
            assertThat(Collections.list(verified.getHeaders("X-User-Role"))).containsExactly("USER");
            assertThat(verified.getHeader("X-Account-Id")).isNull();
            assertThat(verified.getHeader("X-Workspace-Role")).isNull();
            assertThat(verified.getHeader("X-Internal-Service-Token")).isNull();
            assertThat(Collections.list(verified.getHeaderNames()))
                    .doesNotContain("X-Account-Id", "X-Capabilities", "X-Workspace-Role");
            assertThat(verified.getHeader("X-Correlation-Id")).isEqualTo("trace");
        });
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void ordinaryRuntimeDoesNotTrustTheEphemeralTestKey() throws Exception {
        var runtime = new UserIdentityFilter(new BearerUserIdentity(new JwtAccessTokenService(
                "dev-rsa", "", "voltcanary-identity", "voltcanary-api")));
        var request = new MockHttpServletRequest("GET", "/alerts");
        request.addHeader("Authorization", "Bearer " + SignedUserTokens.create(UUID.randomUUID(), "test@example.invalid", "ADMIN"));
        var response = new MockHttpServletResponse();
        runtime.doFilter(request, response, (req, res) -> { throw new AssertionError("Test key trusted"); });
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void normalizedUserRoutesAndNewRoutesCannotFallThroughToInternalExemption() throws Exception {
        for (String path : new String[]{"/devices", "/future-user-resource"}) {
            var request = new MockHttpServletRequest("GET", "/internal/../devices");
            request.setServletPath(path);
            request.addHeader("X-User-Id", UUID.randomUUID().toString());
            request.addHeader("X-User-Role", "ADMIN");
            var response = new MockHttpServletResponse();
            filter.doFilter(request, response, (req, res) -> { throw new AssertionError("Unverified actor reached handler"); });
            assertThat(response.getStatus()).isEqualTo(401);
        }
    }

    @Test
    void internalReadContractRequiresItsOwnCredentialAndRejectsWrites() throws Exception {
        var internal = new InternalTelemetryFilter("isolated-internal");
        for (String method : new String[]{"GET", "POST"}) {
            var request = new MockHttpServletRequest(method, "/internal/telemetry/devices/fixture/readings");
            request.addHeader("X-Internal-Service-Token", "isolated-internal");
            var response = new MockHttpServletResponse();
            AtomicBoolean called = new AtomicBoolean();
            internal.doFilter(request, response, (req, res) -> called.set(true));
            assertThat(called.get()).isEqualTo("GET".equals(method));
            assertThat(response.getStatus()).isEqualTo("GET".equals(method) ? 200 : 401);
        }
        var response = new MockHttpServletResponse();
        internal.doFilter(new MockHttpServletRequest("GET", "/internal/telemetry/devices/fixture/readings"), response,
                (req, res) -> { throw new AssertionError("Missing internal credential accepted"); });
        assertThat(response.getStatus()).isEqualTo(401);
    }
}
