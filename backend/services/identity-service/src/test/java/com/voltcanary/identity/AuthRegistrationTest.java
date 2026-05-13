package com.voltcanary.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.voltcanary.identity.domain.User;
import com.voltcanary.identity.dto.AuthTokenResponse;
import com.voltcanary.identity.dto.MeResponse;
import com.voltcanary.identity.dto.RegisterResponse;
import com.voltcanary.identity.repository.UserRepository;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.jpa.hibernate.ddl-auto=validate",
                "spring.jpa.open-in-view=false"
        })
class AuthRegistrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void registerCreatesUserWithHashedPassword() {
        ResponseEntity<RegisterResponse> response = restTemplate.postForEntity(
                "/auth/register",
                Map.of("email", "User@example.com", "password", "Password123!"),
                RegisterResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().email()).isEqualTo("user@example.com");
        assertThat(response.getBody().userId()).isNotNull();

        User saved = userRepository.findByEmail("user@example.com").orElseThrow();
        assertThat(saved.getId()).isEqualTo(response.getBody().userId());
        assertThat(saved.getRole()).isEqualTo("USER");
        assertThat(saved.getPasswordHash()).isNotEqualTo("Password123!");
        assertThat(passwordEncoder.matches("Password123!", saved.getPasswordHash())).isTrue();
    }

    @Test
    void registerRejectsDuplicateEmail() {
        Map<String, String> request = Map.of("email", "duplicate@example.com", "password", "Password123!");

        ResponseEntity<RegisterResponse> first = restTemplate.postForEntity(
                "/auth/register",
                request,
                RegisterResponse.class);
        ResponseEntity<String> second = restTemplate.postForEntity(
                "/auth/register",
                request,
                String.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody()).contains("duplicate_email");
    }

    @Test
    void registerRejectsWeakPassword() {
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/auth/register",
                Map.of("email", "weak@example.com", "password", "password"),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("validation_failed");
    }

    @Test
    void loginReturnsTokens() {
        register("login@example.com", "Password123!");

        ResponseEntity<AuthTokenResponse> response = login("login@example.com", "Password123!");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().accessToken()).isNotBlank();
        assertThat(response.getBody().refreshToken()).isNotBlank();
        assertThat(response.getBody().tokenType()).isEqualTo("Bearer");
        assertThat(response.getBody().expiresIn()).isEqualTo(900);
    }

    @Test
    void loginRejectsWrongPassword() {
        register("wrong-password@example.com", "Password123!");

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/auth/login",
                Map.of("email", "wrong-password@example.com", "password", "Wrong123!"),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).contains("authentication_failed");
    }

    @Test
    void meReturnsCurrentUserWithValidToken() {
        register("me@example.com", "Password123!");
        AuthTokenResponse tokens = login("me@example.com", "Password123!").getBody();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(tokens.accessToken());
        ResponseEntity<MeResponse> response = restTemplate.exchange(
                "/auth/me",
                org.springframework.http.HttpMethod.GET,
                new HttpEntity<>(headers),
                MeResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().email()).isEqualTo("me@example.com");
        assertThat(response.getBody().role()).isEqualTo("USER");
        assertThat(response.getBody().id()).isNotNull();
    }

    @Test
    void refreshRotatesRefreshToken() {
        register("refresh@example.com", "Password123!");
        AuthTokenResponse tokens = login("refresh@example.com", "Password123!").getBody();

        ResponseEntity<AuthTokenResponse> response = restTemplate.postForEntity(
                "/auth/refresh",
                Map.of("refreshToken", tokens.refreshToken()),
                AuthTokenResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().accessToken()).isNotBlank();
        assertThat(response.getBody().refreshToken()).isNotBlank();
        assertThat(response.getBody().refreshToken()).isNotEqualTo(tokens.refreshToken());
        assertThat(response.getBody().tokenType()).isEqualTo("Bearer");
    }

    @Test
    void refreshRejectsRevokedToken() {
        register("revoked@example.com", "Password123!");
        AuthTokenResponse tokens = login("revoked@example.com", "Password123!").getBody();

        ResponseEntity<AuthTokenResponse> firstRefresh = restTemplate.postForEntity(
                "/auth/refresh",
                Map.of("refreshToken", tokens.refreshToken()),
                AuthTokenResponse.class);
        ResponseEntity<String> secondRefresh = restTemplate.postForEntity(
                "/auth/refresh",
                Map.of("refreshToken", tokens.refreshToken()),
                String.class);

        assertThat(firstRefresh.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(secondRefresh.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(secondRefresh.getBody()).contains("authentication_failed");
    }

    private RegisterResponse register(String email, String password) {
        return restTemplate.postForEntity(
                        "/auth/register",
                        Map.of("email", email, "password", password),
                        RegisterResponse.class)
                .getBody();
    }

    private ResponseEntity<AuthTokenResponse> login(String email, String password) {
        return restTemplate.postForEntity(
                "/auth/login",
                Map.of("email", email, "password", password),
                AuthTokenResponse.class);
    }
}
