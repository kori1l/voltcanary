package com.voltcanary.device.security;

import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.voltcanary.device.shared.AuthenticationFailedException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Date;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

    private final byte[] signingKey;

    public JwtService(@Value("${app.jwt.secret:${JWT_SECRET:change_me}}") String secret) {
        this.signingKey = sha256(secret);
    }

    public AuthenticatedUser parseBearerToken(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            throw new AuthenticationFailedException("Missing bearer token");
        }
        return parseAccessToken(authorizationHeader.substring("Bearer ".length()));
    }

    private AuthenticatedUser parseAccessToken(String token) {
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
        } catch (AuthenticationFailedException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new AuthenticationFailedException("Invalid access token");
        }
    }

    private byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to initialize JWT signing key", exception);
        }
    }
}
