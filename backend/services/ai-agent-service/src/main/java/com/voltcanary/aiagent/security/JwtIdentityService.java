package com.voltcanary.aiagent.security;

import com.voltcanary.common.security.JwtAccessTokenClaims;
import com.voltcanary.common.security.JwtAccessTokenService;
import com.voltcanary.aiagent.config.AiAgentJwtProperties;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class JwtIdentityService {
    private final JwtAccessTokenService accessTokenService;

    public JwtIdentityService(AiAgentJwtProperties properties) {
        this.accessTokenService = new JwtAccessTokenService(
                properties.getKeyId(),
                properties.getPublicKeys(),
                properties.getIssuer(),
                properties.getAudience());
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
