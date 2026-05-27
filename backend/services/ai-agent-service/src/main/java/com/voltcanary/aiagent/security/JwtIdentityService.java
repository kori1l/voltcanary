package com.voltcanary.aiagent.security;

import com.voltcanary.common.security.JwtAccessTokenClaims;
import com.voltcanary.common.security.JwtAccessTokenService;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class JwtIdentityService {
    private final JwtAccessTokenService accessTokenService;

    public JwtIdentityService(
            @Value("${app.jwt.key-id:${JWT_KEY_ID:dev-rsa}}") String activeKeyId,
            @Value("${app.jwt.public-keys:${JWT_PUBLIC_KEYS:}}") String publicKeys,
            @Value("${app.jwt.issuer:${JWT_ISSUER:voltcanary-identity}}") String issuer,
            @Value("${app.jwt.audience:${JWT_AUDIENCE:voltcanary-api}}") String audience) {
        this.accessTokenService = new JwtAccessTokenService(activeKeyId, publicKeys, issuer, audience);
    }

    public AuthenticatedUser parseBearerToken(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            throw new AuthenticationFailedException("Missing bearer token");
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
}
