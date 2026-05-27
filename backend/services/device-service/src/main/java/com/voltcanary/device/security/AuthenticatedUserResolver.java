package com.voltcanary.device.security;

import com.voltcanary.common.security.JwtAccessTokenClaims;
import com.voltcanary.common.security.JwtAccessTokenService;
import com.voltcanary.device.shared.AuthenticationFailedException;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;

@Service
public class AuthenticatedUserResolver {

    public static final String USER_ID_HEADER = "X-User-Id";
    public static final String USER_EMAIL_HEADER = "X-User-Email";
    public static final String USER_ROLE_HEADER = "X-User-Role";

    private final JwtAccessTokenService accessTokenService;

    public AuthenticatedUserResolver(
            @Value("${app.jwt.key-id:${JWT_KEY_ID:dev-rsa}}") String activeKeyId,
            @Value("${app.jwt.public-keys:${JWT_PUBLIC_KEYS:}}") String publicKeys,
            @Value("${app.jwt.issuer:${JWT_ISSUER:voltcanary-identity}}") String issuer,
            @Value("${app.jwt.audience:${JWT_AUDIENCE:voltcanary-api}}") String audience) {
        this.accessTokenService = new JwtAccessTokenService(activeKeyId, publicKeys, issuer, audience);
    }

    public AuthenticatedUser resolve(HttpHeaders headers) {
        AuthenticatedUser headerUser = fromGatewayHeaders(headers);
        if (headerUser != null) {
            return headerUser;
        }
        return fromBearerToken(headers.getFirst(HttpHeaders.AUTHORIZATION));
    }

    private AuthenticatedUser fromGatewayHeaders(HttpHeaders headers) {
        String userId = headers.getFirst(USER_ID_HEADER);
        String email = headers.getFirst(USER_EMAIL_HEADER);
        String role = headers.getFirst(USER_ROLE_HEADER);
        if (isBlank(userId) && isBlank(email) && isBlank(role)) {
            return null;
        }
        if (isBlank(userId) || isBlank(email) || isBlank(role)) {
            throw new AuthenticationFailedException("Incomplete gateway user headers");
        }
        try {
            return new AuthenticatedUser(UUID.fromString(userId), email, role);
        } catch (IllegalArgumentException exception) {
            throw new AuthenticationFailedException("Invalid gateway user headers");
        }
    }

    private AuthenticatedUser fromBearerToken(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            throw new AuthenticationFailedException("Missing authenticated user");
        }
        try {
            JwtAccessTokenClaims claims = accessTokenService.validate(authorizationHeader.substring("Bearer ".length()));
            return new AuthenticatedUser(UUID.fromString(claims.subject()), claims.email(), claims.role());
        } catch (AuthenticationFailedException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new AuthenticationFailedException("Invalid access token");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
