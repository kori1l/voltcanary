package com.voltcanary.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.voltcanary.common.security.JwtAccessTokenService;
import com.voltcanary.identity.domain.User;
import com.voltcanary.identity.domain.UserRole;
import com.voltcanary.identity.domain.UserStatus;
import com.voltcanary.identity.dto.AuthTokenResponse;
import com.voltcanary.identity.dto.MeResponse;
import com.voltcanary.identity.dto.RegisterResponse;
import com.voltcanary.identity.repository.LoginAuditRepository;
import com.voltcanary.identity.repository.RefreshTokenRepository;
import com.voltcanary.identity.repository.UserRepository;
import com.voltcanary.identity.security.RefreshTokenService;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.jpa.hibernate.ddl-auto=validate",
                "spring.jpa.open-in-view=false",
                "app.auth.login.max-attempts-per-email=100",
                "app.auth.login.max-attempts-per-ip=1000"
        })
class AuthRegistrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private LoginAuditRepository loginAuditRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private RefreshTokenService refreshTokenService;

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
        assertThat(saved.getRole()).isEqualTo(UserRole.USER);
        assertThat(saved.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(saved.isEmailVerified()).isFalse();
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
        assertAccessTokenClaims(response.getBody().accessToken());
    }

    @Test
    void loginRejectsWrongPassword() {
        register("wrong-password@example.com", "Password123!");
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Forwarded-For", "203.0.113.10");
        headers.set("User-Agent", "VoltCanary-Test/1.0");
        headers.set("X-Correlation-Id", "audit-correlation-id");

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/auth/login",
                new HttpEntity<>(Map.of("email", "wrong-password@example.com", "password", "Wrong123!"), headers),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).contains("authentication_failed");
        assertThat(loginAuditRepository.findAll())
                .anySatisfy(audit -> {
                    assertThat(audit.getEmail()).isEqualTo("wrong-password@example.com");
                    assertThat(audit.isSuccessful()).isFalse();
                    assertThat(audit.getFailureReason()).isEqualTo("wrong_password");
                    assertThat(audit.getFailureCategory()).isEqualTo("wrong_password");
                    assertThat(audit.getIpAddress()).isEqualTo("203.0.113.10");
                    assertThat(audit.getUserAgent()).isEqualTo("VoltCanary-Test/1.0");
                    assertThat(audit.getCorrelationId()).isEqualTo("audit-correlation-id");
                });
    }

    @Test
    void loginTemporarilyLocksEmailAfterRepeatedFailures() {
        register("locked-login@example.com", "Password123!");

        for (int attempt = 0; attempt < 5; attempt++) {
            ResponseEntity<String> response = restTemplate.postForEntity(
                    "/auth/login",
                    Map.of("email", "locked-login@example.com", "password", "Wrong123!"),
                    String.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(response.getBody()).contains("authentication_failed");
        }

        ResponseEntity<String> lockedResponse = restTemplate.postForEntity(
                "/auth/login",
                Map.of("email", "locked-login@example.com", "password", "Password123!"),
                String.class);

        assertThat(lockedResponse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(lockedResponse.getBody()).contains("authentication_failed");
        assertThat(lockedResponse.getBody()).doesNotContain("locked");
    }

    @Test
    void loginRejectsDisabledUser() {
        register("disabled-login@example.com", "Password123!");
        User user = userRepository.findByEmail("disabled-login@example.com").orElseThrow();
        user.setStatus(UserStatus.DISABLED);
        userRepository.save(user);

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/auth/login",
                Map.of("email", "disabled-login@example.com", "password", "Password123!"),
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
        assertThat(response.getBody().status()).isEqualTo(UserStatus.ACTIVE);
        assertThat(response.getBody().emailVerified()).isFalse();
        assertThat(response.getBody().id()).isNotNull();
    }

    @Test
    void adminCanListUsersChangeRoleAndRevokeUserSessions() {
        register("admin@example.com", "Password123!");
        register("managed@example.com", "Password123!");
        User admin = userRepository.findByEmail("admin@example.com").orElseThrow();
        admin.setRole(UserRole.ADMIN);
        userRepository.save(admin);
        User managed = userRepository.findByEmail("managed@example.com").orElseThrow();
        AuthTokenResponse adminTokens = login("admin@example.com", "Password123!").getBody();
        AuthTokenResponse managedTokens = login("managed@example.com", "Password123!").getBody();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(adminTokens.accessToken());
        ResponseEntity<String> listedUsers = restTemplate.exchange(
                "/auth/admin/users",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class);
        ResponseEntity<String> roleUpdate = restTemplate.exchange(
                "/auth/admin/users/" + managed.getId() + "/role",
                HttpMethod.PATCH,
                new HttpEntity<>(Map.of("role", "SUPPORT"), headers),
                String.class);
        ResponseEntity<String> sessions = restTemplate.exchange(
                "/auth/admin/users/" + managed.getId() + "/sessions",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class);
        ResponseEntity<String> revoke = restTemplate.exchange(
                "/auth/admin/users/" + managed.getId() + "/sessions/revoke",
                HttpMethod.POST,
                new HttpEntity<>(headers),
                String.class);
        ResponseEntity<String> refreshAfterRevoke = restTemplate.postForEntity(
                "/auth/refresh",
                Map.of("refreshToken", managedTokens.refreshToken()),
                String.class);

        assertThat(listedUsers.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listedUsers.getBody()).contains("admin@example.com", "managed@example.com");
        assertThat(roleUpdate.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(userRepository.findById(managed.getId()).orElseThrow().getRole()).isEqualTo(UserRole.SUPPORT);
        assertThat(sessions.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(sessions.getBody()).contains(managed.getId().toString(), "\"active\":true");
        assertThat(revoke.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(revoke.getBody()).contains("revokedCount");
        assertThat(refreshAfterRevoke.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void adminEndpointsRejectRegularUsers() {
        register("regular-admin-check@example.com", "Password123!");
        AuthTokenResponse tokens = login("regular-admin-check@example.com", "Password123!").getBody();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(tokens.accessToken());
        ResponseEntity<String> response = restTemplate.exchange(
                "/auth/admin/users",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).contains("authorization_failed");
    }

    @Test
    void meRejectsInvalidAccessToken() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("not-a-jwt");

        ResponseEntity<String> response = restTemplate.exchange(
                "/auth/me",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).contains("authentication_failed");
    }

    @Test
    void meRejectsExpiredAccessToken() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(expiredAccessToken());

        ResponseEntity<String> response = restTemplate.exchange(
                "/auth/me",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).contains("authentication_failed");
    }

    @Test
    void meRejectsNonAccessTokenType() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(signedAccessTokenWithType("refresh", Instant.now().plusSeconds(900)));

        ResponseEntity<String> response = restTemplate.exchange(
                "/auth/me",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).contains("authentication_failed");
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

    @Test
    void refreshTokenReuseRevokesAllUserRefreshTokens() {
        register("reuse@example.com", "Password123!");
        AuthTokenResponse originalTokens = login("reuse@example.com", "Password123!").getBody();
        AuthTokenResponse secondSessionTokens = login("reuse@example.com", "Password123!").getBody();
        ResponseEntity<AuthTokenResponse> rotated = restTemplate.postForEntity(
                "/auth/refresh",
                Map.of("refreshToken", originalTokens.refreshToken()),
                AuthTokenResponse.class);

        ResponseEntity<String> reusedOriginalRefresh = restTemplate.postForEntity(
                "/auth/refresh",
                Map.of("refreshToken", originalTokens.refreshToken()),
                String.class);
        ResponseEntity<String> rotatedRefreshAfterReuse = restTemplate.postForEntity(
                "/auth/refresh",
                Map.of("refreshToken", rotated.getBody().refreshToken()),
                String.class);
        ResponseEntity<String> secondSessionRefreshAfterReuse = restTemplate.postForEntity(
                "/auth/refresh",
                Map.of("refreshToken", secondSessionTokens.refreshToken()),
                String.class);

        assertThat(rotated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(reusedOriginalRefresh.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(reusedOriginalRefresh.getBody()).contains("authentication_failed");
        assertThat(rotatedRefreshAfterReuse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(rotatedRefreshAfterReuse.getBody()).contains("authentication_failed");
        assertThat(secondSessionRefreshAfterReuse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(secondSessionRefreshAfterReuse.getBody()).contains("authentication_failed");
    }

    @Test
    void refreshRejectsExpiredToken() {
        register("expired-refresh@example.com", "Password123!");
        AuthTokenResponse tokens = login("expired-refresh@example.com", "Password123!").getBody();
        var refreshToken = refreshTokenRepository.findByTokenHash(refreshTokenService.hash(tokens.refreshToken()))
                .orElseThrow();
        refreshToken.setExpiresAt(Instant.now().minusSeconds(1));
        refreshTokenRepository.save(refreshToken);

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/auth/refresh",
                Map.of("refreshToken", tokens.refreshToken()),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).contains("authentication_failed");
    }

    @Test
    void refreshRejectsLockedUser() {
        register("locked-refresh@example.com", "Password123!");
        AuthTokenResponse tokens = login("locked-refresh@example.com", "Password123!").getBody();
        User user = userRepository.findByEmail("locked-refresh@example.com").orElseThrow();
        user.setStatus(UserStatus.LOCKED);
        userRepository.save(user);

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/auth/refresh",
                Map.of("refreshToken", tokens.refreshToken()),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).contains("authentication_failed");
    }

    @Test
    void logoutRevokesRefreshToken() {
        register("logout@example.com", "Password123!");
        AuthTokenResponse tokens = login("logout@example.com", "Password123!").getBody();

        ResponseEntity<String> logout = restTemplate.postForEntity(
                "/auth/logout",
                Map.of("refreshToken", tokens.refreshToken()),
                String.class);
        ResponseEntity<String> refreshAfterLogout = restTemplate.postForEntity(
                "/auth/refresh",
                Map.of("refreshToken", tokens.refreshToken()),
                String.class);

        assertThat(logout.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(logout.getBody()).contains("logout successful");
        assertThat(refreshAfterLogout.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(refreshAfterLogout.getBody()).contains("authentication_failed");
    }

    @Test
    void logoutAllRevokesAllRefreshTokensForCurrentUser() {
        register("logout-all@example.com", "Password123!");
        AuthTokenResponse firstTokens = login("logout-all@example.com", "Password123!").getBody();
        AuthTokenResponse secondTokens = login("logout-all@example.com", "Password123!").getBody();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(firstTokens.accessToken());
        ResponseEntity<String> logoutAll = restTemplate.exchange(
                "/auth/logout-all",
                HttpMethod.POST,
                new HttpEntity<>(headers),
                String.class);
        ResponseEntity<String> firstRefreshAfterLogoutAll = restTemplate.postForEntity(
                "/auth/refresh",
                Map.of("refreshToken", firstTokens.refreshToken()),
                String.class);
        ResponseEntity<String> secondRefreshAfterLogoutAll = restTemplate.postForEntity(
                "/auth/refresh",
                Map.of("refreshToken", secondTokens.refreshToken()),
                String.class);

        assertThat(logoutAll.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(logoutAll.getBody()).contains("logout-all successful");
        assertThat(firstRefreshAfterLogoutAll.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(firstRefreshAfterLogoutAll.getBody()).contains("authentication_failed");
        assertThat(secondRefreshAfterLogoutAll.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(secondRefreshAfterLogoutAll.getBody()).contains("authentication_failed");
    }

    @Test
    void changePasswordRequiresCurrentPasswordAndRevokesRefreshTokens() {
        register("change-password@example.com", "Password123!");
        AuthTokenResponse tokens = login("change-password@example.com", "Password123!").getBody();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(tokens.accessToken());
        ResponseEntity<String> wrongCurrentPassword = restTemplate.exchange(
                "/auth/change-password",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "currentPassword", "Wrong123!",
                        "newPassword", "NewPassword123!"), headers),
                String.class);
        ResponseEntity<String> changedPassword = restTemplate.exchange(
                "/auth/change-password",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "currentPassword", "Password123!",
                        "newPassword", "NewPassword123!"), headers),
                String.class);
        ResponseEntity<String> refreshAfterPasswordChange = restTemplate.postForEntity(
                "/auth/refresh",
                Map.of("refreshToken", tokens.refreshToken()),
                String.class);
        ResponseEntity<AuthTokenResponse> oldPasswordLogin = restTemplate.postForEntity(
                "/auth/login",
                Map.of("email", "change-password@example.com", "password", "Password123!"),
                AuthTokenResponse.class);
        ResponseEntity<AuthTokenResponse> newPasswordLogin = restTemplate.postForEntity(
                "/auth/login",
                Map.of("email", "change-password@example.com", "password", "NewPassword123!"),
                AuthTokenResponse.class);

        assertThat(wrongCurrentPassword.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(changedPassword.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(changedPassword.getBody()).contains("password changed");
        assertThat(refreshAfterPasswordChange.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(refreshAfterPasswordChange.getBody()).contains("authentication_failed");
        assertThat(oldPasswordLogin.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(newPasswordLogin.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void forgotPasswordIssuesOneTimeResetToken() {
        register("reset@example.com", "Password123!");
        AuthTokenResponse tokens = login("reset@example.com", "Password123!").getBody();
        ResponseEntity<String> resetRequest = restTemplate.postForEntity(
                "/auth/forgot-password",
                Map.of("email", "reset@example.com"),
                String.class);
        String resetToken = tokenFromMessage(resetRequest.getBody());

        ResponseEntity<String> reset = restTemplate.postForEntity(
                "/auth/reset-password",
                Map.of("token", resetToken, "newPassword", "ResetPassword123!"),
                String.class);
        ResponseEntity<String> reusedReset = restTemplate.postForEntity(
                "/auth/reset-password",
                Map.of("token", resetToken, "newPassword", "AnotherReset123!"),
                String.class);
        ResponseEntity<String> refreshAfterReset = restTemplate.postForEntity(
                "/auth/refresh",
                Map.of("refreshToken", tokens.refreshToken()),
                String.class);
        ResponseEntity<AuthTokenResponse> oldPasswordLogin = login("reset@example.com", "Password123!");
        ResponseEntity<AuthTokenResponse> newPasswordLogin = login("reset@example.com", "ResetPassword123!");

        assertThat(resetRequest.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(reset.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(reset.getBody()).contains("password reset complete");
        assertThat(reusedReset.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(refreshAfterReset.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(oldPasswordLogin.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(newPasswordLogin.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void emailVerificationMarksUserVerifiedAndRejectsTokenReuse() {
        register("verify@example.com", "Password123!");
        ResponseEntity<String> verificationRequest = restTemplate.postForEntity(
                "/auth/email-verification/request",
                Map.of("email", "verify@example.com"),
                String.class);
        String verificationToken = tokenFromMessage(verificationRequest.getBody());

        ResponseEntity<String> verified = restTemplate.postForEntity(
                "/auth/email-verification/confirm",
                Map.of("token", verificationToken),
                String.class);
        ResponseEntity<String> reusedVerification = restTemplate.postForEntity(
                "/auth/email-verification/confirm",
                Map.of("token", verificationToken),
                String.class);

        User user = userRepository.findByEmail("verify@example.com").orElseThrow();
        assertThat(verificationRequest.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(verified.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(verified.getBody()).contains("email verified");
        assertThat(user.isEmailVerified()).isTrue();
        assertThat(reusedVerification.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void cleanupDeletesOnlyExpiredRefreshTokens() {
        register("cleanup@example.com", "Password123!");
        AuthTokenResponse expiredTokens = login("cleanup@example.com", "Password123!").getBody();
        AuthTokenResponse activeTokens = login("cleanup@example.com", "Password123!").getBody();
        var expiredRefreshToken = refreshTokenRepository.findByTokenHash(refreshTokenService.hash(expiredTokens.refreshToken()))
                .orElseThrow();
        var activeRefreshToken = refreshTokenRepository.findByTokenHash(refreshTokenService.hash(activeTokens.refreshToken()))
                .orElseThrow();
        expiredRefreshToken.setExpiresAt(Instant.now().minusSeconds(1));
        refreshTokenRepository.save(expiredRefreshToken);

        long deletedCount = refreshTokenService.deleteExpired();

        assertThat(deletedCount).isEqualTo(1);
        assertThat(refreshTokenRepository.findById(expiredRefreshToken.getId())).isEmpty();
        assertThat(refreshTokenRepository.findById(activeRefreshToken.getId())).isPresent();
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

    private String tokenFromMessage(String body) {
        assertThat(body).isNotBlank();
        return body.substring(body.lastIndexOf(' ') + 1).replace("\"}", "");
    }

    private String expiredAccessToken() {
        return signedAccessTokenWithType("access", Instant.now().minusSeconds(300));
    }

    private String signedAccessTokenWithType(String tokenType, Instant expiresAt) {
        long ttlSeconds = expiresAt.getEpochSecond() - Instant.now().getEpochSecond();
        return new JwtAccessTokenService("change_me", "voltcanary-identity", "voltcanary-api")
                .create(UUID.randomUUID().toString(), "expired@example.com", "USER", ttlSeconds, tokenType);
    }

    private void assertAccessTokenClaims(String token) {
        try {
            JWTClaimsSet claims = SignedJWT.parse(token).getJWTClaimsSet();

            assertThat(claims.getIssuer()).isEqualTo("voltcanary-identity");
            assertThat(claims.getAudience()).contains("voltcanary-api");
            assertThat(claims.getJWTID()).isNotBlank();
            assertThat(claims.getStringClaim("typ")).isEqualTo("access");
            assertThat(SignedJWT.parse(token).getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.RS256);
            assertThat(SignedJWT.parse(token).getHeader().getKeyID()).isEqualTo(JwtAccessTokenService.DEV_KEY_ID);
        } catch (Exception exception) {
            throw new AssertionError("Failed to parse access token", exception);
        }
    }
}
