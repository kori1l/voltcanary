package com.voltcanary.device.security;

import com.voltcanary.common.security.JwtAccessTokenClaims;
import com.voltcanary.common.security.JwtAccessTokenService;
import com.voltcanary.device.config.DeviceJwtProperties;
import com.voltcanary.device.domain.Device;
import com.voltcanary.device.shared.AuthenticationFailedException;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;


@Service
public class AuthenticatedUserResolver {

    public static final String USER_ID_HEADER = "X-User-Id";
    public static final String USER_EMAIL_HEADER = "X-User-Email";
    public static final String USER_ROLE_HEADER = "X-User-Role";

    private final JwtAccessTokenService accessTokenService;

    public AuthenticatedUserResolver(DeviceJwtProperties properties) {
        this.accessTokenService = new JwtAccessTokenService(
                properties.getKeyId(),
                properties.getPublicKeys(),
                properties.getIssuer(),
                properties.getAudience());
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
