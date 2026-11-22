package com.voltcanary.common.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Short-lived machine assertion for one exact read. It is never a user access token or owner grant. */
public final class VclWorkerAssertion {

    public static final String TYPE = "vcl-worker-read-1";
    private static final String ISSUER = "voltcanary-analytics-worker";
    private static final Set<String> FIELDS = Set.of(
        "iss",
        "aud",
        "jti",
        "typ",
        "iat",
        "exp",
        "actor",
        "account",
        "rule",
        "epoch",
        "grant",
        "target",
        "operation",
        "requestDigest"
    );

    public record Scope(
        UUID actor,
        UUID account,
        UUID rule,
        UUID epoch,
        UUID grant,
        UUID target,
        String operation,
        String requestDigest
    ) {
        public Scope {
            Objects.requireNonNull(actor);
            Objects.requireNonNull(account);
            Objects.requireNonNull(rule);
            Objects.requireNonNull(epoch);
            Objects.requireNonNull(grant);
            Objects.requireNonNull(target);
            if (
                operation == null ||
                !operation.matches("[A-Z_]{3,48}") ||
                requestDigest == null ||
                !requestDigest.matches("[0-9a-f]{64}")
            ) {
                throw new IllegalArgumentException("Invalid worker scope");
            }
        }
    }

    private final byte[] key;
    private final String keyId;
    private final String audience;
    private final Clock clock;

    public VclWorkerAssertion(byte[] key, String keyId, String audience, Clock clock) {
        if (
            key == null ||
            key.length < 32 ||
            key.length > 128 ||
            keyId == null ||
            !keyId.matches("[A-Za-z0-9_-]{1,32}") ||
            audience == null ||
            !audience.matches("[a-z][a-z0-9-]{2,63}")
        ) {
            throw new IllegalArgumentException("Worker signing key unavailable");
        }
        this.key = key.clone();
        this.keyId = keyId;
        this.audience = audience;
        this.clock = Objects.requireNonNull(clock);
    }

    public String sign(Scope scope, int seconds) {
        Objects.requireNonNull(scope);
        if (seconds < 1 || seconds > 30) throw new IllegalArgumentException(
            "Invalid worker assertion lifetime"
        );
        Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        var claims = new JWTClaimsSet.Builder()
            .issuer(ISSUER)
            .audience(audience)
            .jwtID(UUID.randomUUID().toString())
            .claim("typ", TYPE)
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plusSeconds(seconds)))
            .claim("actor", scope.actor().toString())
            .claim("account", scope.account().toString())
            .claim("rule", scope.rule().toString())
            .claim("epoch", scope.epoch().toString())
            .claim("grant", scope.grant().toString())
            .claim("target", scope.target().toString())
            .claim("operation", scope.operation())
            .claim("requestDigest", scope.requestDigest())
            .build();
        try {
            var token = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.HS256).keyID(keyId).build(),
                claims
            );
            token.sign(new MACSigner(key));
            return token.serialize();
        } catch (Exception failure) {
            throw new IllegalStateException("Worker assertion signing failed", failure);
        }
    }

    public Scope verify(String serialized, String operation, UUID target, String requestDigest) {
        try {
            if (
                serialized == null || serialized.length() > 4096
            ) throw new IllegalArgumentException();
            var token = SignedJWT.parse(serialized);
            if (
                !JWSAlgorithm.HS256.equals(token.getHeader().getAlgorithm()) ||
                !keyId.equals(token.getHeader().getKeyID()) ||
                !token.verify(new MACVerifier(key))
            ) throw new IllegalArgumentException();
            var claims = token.getJWTClaimsSet();
            Instant now = clock.instant();
            if (
                !FIELDS.equals(claims.getClaims().keySet()) ||
                !ISSUER.equals(claims.getIssuer()) ||
                !java.util.List.of(audience).equals(claims.getAudience()) ||
                !TYPE.equals(claims.getStringClaim("typ")) ||
                claims.getIssueTime() == null ||
                claims.getExpirationTime() == null ||
                now.isBefore(claims.getIssueTime().toInstant()) ||
                !now.isBefore(claims.getExpirationTime().toInstant()) ||
                claims
                    .getExpirationTime()
                    .toInstant()
                    .isAfter(claims.getIssueTime().toInstant().plusSeconds(30))
            ) {
                throw new IllegalArgumentException();
            }
            UUID.fromString(claims.getJWTID());
            var scope = new Scope(
                uuid(claims, "actor"),
                uuid(claims, "account"),
                uuid(claims, "rule"),
                uuid(claims, "epoch"),
                uuid(claims, "grant"),
                uuid(claims, "target"),
                claims.getStringClaim("operation"),
                claims.getStringClaim("requestDigest")
            );
            if (
                !scope.operation().equals(operation) ||
                !scope.target().equals(target) ||
                !scope.requestDigest().equals(requestDigest)
            ) throw new IllegalArgumentException();
            return scope;
        } catch (Exception failure) {
            throw new IllegalArgumentException("Invalid worker assertion");
        }
    }

    public static String requestDigest(String method, String resource, byte[] body) {
        if (
            method == null ||
            !method.matches("GET|POST") ||
            resource == null ||
            !resource.startsWith("/internal/vcl/") ||
            resource.length() > 512 ||
            resource.indexOf('\n') >= 0 ||
            body == null ||
            body.length > 262_144
        ) {
            throw new IllegalArgumentException("Invalid worker request");
        }
        try {
            var bytes = MessageDigest.getInstance("SHA-256").digest(body);
            var framing = (
                method +
                "\n" +
                resource +
                "\n" +
                java.util.HexFormat.of().formatHex(bytes)
            ).getBytes(StandardCharsets.UTF_8);
            return java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(framing)
            );
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static UUID uuid(JWTClaimsSet claims, String name) throws java.text.ParseException {
        String raw = claims.getStringClaim(name);
        UUID value = UUID.fromString(raw);
        if (!value.toString().equals(raw)) throw new IllegalArgumentException();
        return value;
    }
}
