package com.voltcanary.identity.security;

import com.voltcanary.common.security.JwtAccessTokenClaims;
import com.voltcanary.common.security.JwtAccessTokenService;
import com.voltcanary.identity.domain.User;
import com.voltcanary.identity.exception.AuthenticationFailedException;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

    private final JwtAccessTokenService accessTokenService;
    private final long accessTokenTtlSeconds;

    public JwtService(
            @Value("${app.jwt.key-id:${JWT_KEY_ID:dev-rsa}}") String activeKeyId,
            @Value("${app.jwt.private-key:${JWT_PRIVATE_KEY:}}") String privateKey,
            @Value("${app.jwt.public-keys:${JWT_PUBLIC_KEYS:}}") String publicKeys,
            @Value("${app.jwt.access-token-ttl-seconds:${JWT_ACCESS_TOKEN_TTL_SECONDS:900}}")
                    long accessTokenTtlSeconds,
            @Value("${app.jwt.issuer:${JWT_ISSUER:voltcanary-identity}}") String issuer,
            @Value("${app.jwt.audience:${JWT_AUDIENCE:voltcanary-api}}") String audience) {
        this.accessTokenService = new JwtAccessTokenService(activeKeyId, privateKey, publicKeys, issuer, audience);
        this.accessTokenTtlSeconds = accessTokenTtlSeconds;
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
