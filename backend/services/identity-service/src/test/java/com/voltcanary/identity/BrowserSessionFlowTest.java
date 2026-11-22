package com.voltcanary.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@AutoConfigureTestRestTemplate
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = { "spring.jpa.open-in-view=false", "app.auth.login.max-attempts-per-ip=1000" }
)
class BrowserSessionFlowTest {

    @Autowired
    private TestRestTemplate rest;

    private HttpEntity<?> request(Object body, String origin, String cookie) {
        var headers = new HttpHeaders();
        if (origin != null) headers.setOrigin(origin);
        if (cookie != null) headers.set(HttpHeaders.COOKIE, cookie);
        return new HttpEntity<>(body, headers);
    }

    private String cookie(ResponseEntity<?> response) {
        return response.getHeaders().getFirst(HttpHeaders.SET_COOKIE).split(";", 2)[0];
    }

    @Test
    void loginRotatesAndRevokesHttpOnlyCredentialsWithoutReturningThemInJson() {
        String email = UUID.randomUUID() + "@example.test";
        var credentials = Map.of("email", email, "password", "Password123!");
        assertThat(
            rest.postForEntity("/auth/register", credentials, String.class).getStatusCode()
        ).isEqualTo(HttpStatus.OK);
        String path = "/auth/browser/" + UUID.randomUUID();
        var login = rest.exchange(
            path + "/login",
            HttpMethod.POST,
            request(credentials, "http://localhost:3000", null),
            Map.class
        );
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(login.getBody()).containsOnlyKeys(
            "sessionId",
            "accessToken",
            "tokenType",
            "expiresIn"
        );
        assertThat(login.getHeaders().getFirst(HttpHeaders.SET_COOKIE)).contains(
            "HttpOnly",
            "Secure",
            "SameSite=Strict",
            "Path=" + path
        );
        assertThat(login.getHeaders().getCacheControl()).isEqualTo("no-store");

        var refresh = rest.exchange(
            path + "/refresh",
            HttpMethod.POST,
            request(Map.of(), "http://localhost:3000", cookie(login)),
            Map.class
        );
        assertThat(refresh.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(refresh.getBody()).doesNotContainKey("refreshToken");
        assertThat(cookie(refresh)).isNotEqualTo(cookie(login));
        var logout = rest.exchange(
            path + "/logout",
            HttpMethod.POST,
            request(Map.of(), "http://localhost:3000", cookie(refresh)),
            String.class
        );
        assertThat(logout.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(logout.getHeaders().getFirst(HttpHeaders.SET_COOKIE)).contains(
            "Max-Age=0",
            "Path=" + path
        );
        assertThat(
            rest
                .exchange(
                    path + "/refresh",
                    HttpMethod.POST,
                    request(Map.of(), "http://localhost:3000", cookie(refresh)),
                    String.class
                )
                .getStatusCode()
        ).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void browserMutationsRejectMissingOrUntrustedOriginsBeforeAccessingCredentials() {
        String path = "/auth/browser/" + UUID.randomUUID();
        for (String endpoint : new String[] { "refresh", "logout" }) {
            for (String origin : new String[] { null, "https://attacker.example" }) {
                var response = rest.exchange(
                    path + "/" + endpoint,
                    HttpMethod.POST,
                    request(Map.of(), origin, "voltcanary.browser-refresh=untrusted"),
                    String.class
                );
                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                assertThat(response.getHeaders().getFirst(HttpHeaders.SET_COOKIE)).isNull();
            }
        }
        var response = rest.exchange(
            path + "/login",
            HttpMethod.POST,
            request(
                Map.of("email", "test@example.test", "password", "Password123!"),
                "https://attacker.example",
                null
            ),
            String.class
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
