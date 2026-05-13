package com.voltcanary.identity.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.voltcanary.identity.domain.User;
import com.voltcanary.identity.exception.AuthenticationFailedException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.ParseException;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

    private final byte[] signingKey;
    private final long accessTokenTtlSeconds;

    public JwtService(
            @Value("${app.jwt.secret:${JWT_SECRET:change_me}}") String secret,
            @Value("${app.jwt.access-token-ttl-seconds:${JWT_ACCESS_TOKEN_TTL_SECONDS:900}}")
                    long accessTokenTtlSeconds) {
        this.signingKey = sha256(secret);
        this.accessTokenTtlSeconds = accessTokenTtlSeconds;
    }

    public String createAccessToken(User user) {
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(accessTokenTtlSeconds);

        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(user.getId().toString())
                .claim("email", user.getEmail())
                .claim("role", user.getRole())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(expiresAt))
                .build();

        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(signingKey));
            return jwt.serialize();
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to create access token", exception);
        }
    }

    public AuthenticatedUser parseAccessToken(String token) {
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            JWSVerifier verifier = new MACVerifier(signingKey);
            if (!jwt.verify(verifier)) {
                throw new AuthenticationFailedException("Invalid access token signature");
            }

            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            Date expiresAt = claims.getExpirationTime();
            if (expiresAt == null || expiresAt.before(new Date())) {
                throw new AuthenticationFailedException("Access token expired");
            }

            return new AuthenticatedUser(
                    UUID.fromString(claims.getSubject()),
                    claims.getStringClaim("email"),
                    claims.getStringClaim("role"));
        } catch (ParseException exception) {
            throw new AuthenticationFailedException("Invalid access token");
        } catch (AuthenticationFailedException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new AuthenticationFailedException("Invalid access token");
        }
    }

    public long accessTokenTtlSeconds() {
        return accessTokenTtlSeconds;
    }

    private byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to initialize JWT signing key", exception);
        }
    }
}
