package com.voltcanary.identity.security;

import com.voltcanary.common.security.JwtAccessTokenClaims;
import com.voltcanary.common.security.JwtAccessTokenService;
import com.voltcanary.identity.config.IdentityJwtProperties;
import com.voltcanary.identity.domain.User;
import com.voltcanary.identity.exception.AuthenticationFailedException;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

    private final JwtAccessTokenService accessTokenService;
    private final long accessTokenTtlSeconds;

    public JwtService(IdentityJwtProperties properties) {
        this.accessTokenService = new JwtAccessTokenService(
                properties.getKeyId(),
                properties.getPrivateKey(),
                properties.getPublicKeys(),
                properties.getIssuer(),
                properties.getAudience());
        this.accessTokenTtlSeconds = properties.getAccessTokenTtlSeconds();
    }

    public String createAccessToken(User user) {
        return accessTokenService.create(user.getId().toString(), user.getEmail(), user.getRole().name(), accessTokenTtlSeconds);
    }

    public AuthenticatedUser parseAccessToken(String token) {
        try {
            JwtAccessTokenClaims claims = accessTokenService.validate(token);
            return new AuthenticatedUser(
                    UUID.fromString(claims.subject()),
                    claims.email(),
                    claims.role());
        } catch (Exception exception) {
            throw new AuthenticationFailedException("Invalid access token");
        }
    }

    public long accessTokenTtlSeconds() {
        return accessTokenTtlSeconds;
    }
}
