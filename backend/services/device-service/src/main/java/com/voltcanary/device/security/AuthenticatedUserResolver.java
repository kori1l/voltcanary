package com.voltcanary.device.security;

import com.voltcanary.common.security.JwtAccessTokenClaims;
import com.voltcanary.common.security.JwtAccessTokenService;
import com.voltcanary.common.security.BearerUserIdentity;
import com.voltcanary.device.config.DeviceJwtProperties;
import com.voltcanary.device.exception.AuthenticationFailedException;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;


@Service
public class AuthenticatedUserResolver {

    public static final String USER_ID_HEADER = "X-User-Id";
    public static final String USER_EMAIL_HEADER = "X-User-Email";
    public static final String USER_ROLE_HEADER = "X-User-Role";

    private final BearerUserIdentity identity;

    public AuthenticatedUserResolver(DeviceJwtProperties properties) {
        this.identity = new BearerUserIdentity(new JwtAccessTokenService(
                properties.getKeyId(),
                properties.getPublicKeys(),
                properties.getIssuer(),
                properties.getAudience()));
    }

    public AuthenticatedUser resolve(HttpHeaders headers) {
        return fromBearerToken(headers.getFirst(HttpHeaders.AUTHORIZATION));
    }

    private AuthenticatedUser fromBearerToken(String authorizationHeader) {
        try {
            JwtAccessTokenClaims claims = identity.verify(authorizationHeader);
            return new AuthenticatedUser(UUID.fromString(claims.subject()), claims.email(), claims.role());
        } catch (AuthenticationFailedException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new AuthenticationFailedException("Invalid access token");
        }
    }

}
