package com.voltcanary.common.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class JwtAccessTokenService {

    public static final String ACCESS_TOKEN_TYPE = "access";
    public static final String DEV_KEY_ID = "dev-rsa";
    public static final String DEV_PRIVATE_KEY =
            "MIIEvwIBADANBgkqhkiG9w0BAQEFAASCBKkwggSlAgEAAoIBAQCvOZshEtH0obevcPg4xrPnAgsD98p04y0z5Cxcy/wHUbVn+TIoT5TaIFw9N9K4+i0RkDhEokpjkYIhA//gPFPkfZLh8dgiiMC9XFD4PUQDYNTVNu+kMWAcLm/Fo+t4tPAlRtmmYn6P/9safkCZzbNS1L5vgPqYfMOLn8m9NGwyvhO8skFnXsqCLV/xblkCNY9q2r2UQ9G7v48hLxnmxgubTrE5G0jRnFlX961it55NUUiiEor71L+vej4PZYQMG+YGWOmC26I3I1JRQCTdai2bGuhvkye6YIC1LEnhWr0hf6+uNRSbypwkhlihv7Km3UATFZaMsw+yZCCKK+JpqSrhAgMBAAECggEBAI1/zGkh5JBTQ50WciyPMVocCT80C7bmeWtNGBH729MhRYfn8Xwh/8dBmJsLCEy+IkTrnLS4BbBwIZB9xVSUAv8h9Mom3W3N1y9TbEN6XgEIYfan/U8vXDiFu1bz70QzgTXpB/vXz/KDH7E+QPay6Nu71uj+U99t6Q9IgsYankE+alcRGfd+igufCkljsEAQ0Nr0atEZk6/UoVbpw5L7KwpvThsLuSupWxStgPW5VEsOC+gJs+uZnYhTF6M5r4Eao0T9625Wtmbc6EHk6Kgqa4DLhYvJ+NnEvYxnn4L5xkI1vb+fPutCbLEAF/guyz1+jPzVL1P+zG41gl8bLu3Td5ECgYEA8ipiU8i+ZSQ80poRod8SnOkU2j5LVCIbCz0MtnE7sVBcjK+cgxQqsNYq28dHD5euiooNMxqDpEV5Gguivecf1Pr3/i72gGHGH6Pof3onCFgFQdg1BeupVSI70QD6JcMaqypm5eLF0SQDnmxqoWnRcBUSlCzkJfwC+y6AcPACQQ0CgYEAuTw7WH67ehf5R2XaxmUgIKjwSY7nHcrTb3TEP8Q9BSA1F46g2X0O3ndM7K4TeY30Uoh3/EpzfUfrHLf4QqpEw2dxxSL6/Or5qXcxmn+x8FKQRIecQheiOTlRxLJq43pByJb0ukidWquLxer7B7ojN6leiwtJdjWWwQWFYkWT1CUCgYEAiwVH819f5gJapHBsROgh8FBidzOh3QfDTL1cafQxV4aRyvYZvKo8I86fRhJtugbS3ZeJIlhLUGfNsOUTJT/k50i4S4S8YFFBAvL+ywfeFo0pX3Z2YhK64DPJ8R9Fe5441iB+d+SquXiaVz1YLZfo8xY11zi6NRAqyfYVKECwmpUCgYBgkJLLhn3Ln3XXj7SmWo+OFyP05Od9M4BYCUJJj87Tvj+PQU5/Mt+uf/j9JZ6HbsZgxD3wqwtb00MxHILylfr09xdrUtoPQZvXiYi1Zz5m71PANgXyuolaVZRX+z8XyWND49Y+yp4lZ2qdSeuF0raLTOM5jy/DzRoDFBK98VGodQKBgQCQBTMeIs+CWxcosT7ASZTSLvM2M3e6jT5/K4zhqFF3o23wfYnmFKqpD8Mn+tqGkZ9/9gEvHDqKvzSPbKtSA3FtcqEvIQvmwe6ebUNyXXeHPhQbYkPKMHWaYCrwLfAn6oeY9QvdF4HJIVGel+kHuRR766BxgB4/jROJLvJDrwHvbA==";
    public static final String DEV_PUBLIC_KEY =
            "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEArzmbIRLR9KG3r3D4OMaz5wILA/fKdOMtM+QsXMv8B1G1Z/kyKE+U2iBcPTfSuPotEZA4RKJKY5GCIQP/4DxT5H2S4fHYIojAvVxQ+D1EA2DU1TbvpDFgHC5vxaPreLTwJUbZpmJ+j//bGn5Amc2zUtS+b4D6mHzDi5/JvTRsMr4TvLJBZ17Kgi1f8W5ZAjWPatq9lEPRu7+PIS8Z5sYLm06xORtI0ZxZV/etYreeTVFIohKK+9S/r3o+D2WEDBvmBljpgtuiNyNSUUAk3Wotmxrob5MnumCAtSxJ4Vq9IX+vrjUUm8qcJIZYob+ypt1AExWWjLMPsmQgiiviaakq4QIDAQAB";

    private final String activeKeyId;
    private final RSAPrivateKey privateKey;
    private final Map<String, RSAPublicKey> publicKeys;
    private final String issuer;
    private final String audience;

    public JwtAccessTokenService(String ignoredSecret, String issuer, String audience) {
        this(DEV_KEY_ID, DEV_PRIVATE_KEY, DEV_KEY_ID + "=" + DEV_PUBLIC_KEY, issuer, audience);
    }

    public JwtAccessTokenService(
            String activeKeyId,
            String publicKeys,
            String issuer,
            String audience) {
        this.activeKeyId = blankToDefault(activeKeyId, DEV_KEY_ID);
        this.privateKey = null;
        this.publicKeys = parsePublicKeys(blankToDefault(publicKeys, this.activeKeyId + "=" + DEV_PUBLIC_KEY));
        this.issuer = issuer;
        this.audience = audience;
    }

    public JwtAccessTokenService(
            String activeKeyId,
            String privateKey,
            String publicKeys,
            String issuer,
            String audience) {
        this.activeKeyId = blankToDefault(activeKeyId, DEV_KEY_ID);
        this.privateKey = parsePrivateKey(blankToDefault(privateKey, DEV_PRIVATE_KEY));
        this.publicKeys = parsePublicKeys(blankToDefault(publicKeys, this.activeKeyId + "=" + DEV_PUBLIC_KEY));
        this.issuer = issuer;
        this.audience = audience;
    }

    public String create(String subject, String email, String role, long ttlSeconds) {
        return create(subject, email, role, ttlSeconds, ACCESS_TOKEN_TYPE);
    }

    public String create(String subject, String email, String role, long ttlSeconds, String tokenType) {
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(ttlSeconds);

        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject(subject)
                .audience(audience)
                .jwtID(UUID.randomUUID().toString())
                .claim("typ", tokenType)
                .claim("email", email)
                .claim("role", role)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(expiresAt))
                .build();

        try {
            if (privateKey == null) {
                throw new IllegalStateException("JWT private key is not configured");
            }
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(activeKeyId).build(),
                    claims);
            jwt.sign(new RSASSASigner(privateKey));
            return jwt.serialize();
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to create access token", exception);
        }
    }

    public JwtAccessTokenClaims validate(String token) {
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            String keyId = jwt.getHeader().getKeyID();
            if (keyId == null || keyId.isBlank()) {
                throw new IllegalArgumentException("Invalid access token key id");
            }
            if (!JWSAlgorithm.RS256.equals(jwt.getHeader().getAlgorithm())) {
                throw new IllegalArgumentException("Invalid access token algorithm");
            }
            RSAPublicKey publicKey = publicKeys.get(keyId);
            if (publicKey == null) {
                throw new IllegalArgumentException("Unknown access token key id");
            }
            JWSVerifier verifier = new RSASSAVerifier(publicKey);
            if (!jwt.verify(verifier)) {
                throw new IllegalArgumentException("Invalid access token signature");
            }

            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            Date expiresAt = claims.getExpirationTime();
            if (expiresAt == null || expiresAt.before(new Date())) {
                throw new IllegalArgumentException("Access token expired");
            }
            if (claims.getSubject() == null || claims.getSubject().isBlank()) {
                throw new IllegalArgumentException("Invalid access token subject");
            }
            String email = claims.getStringClaim("email");
            if (email == null || email.isBlank()) {
                throw new IllegalArgumentException("Invalid access token email");
            }
            String role = claims.getStringClaim("role");
            if (role == null || role.isBlank()) {
                throw new IllegalArgumentException("Invalid access token role");
            }
            if (!issuer.equals(claims.getIssuer())) {
                throw new IllegalArgumentException("Invalid access token issuer");
            }
            List<String> audiences = claims.getAudience();
            if (audiences == null || !audiences.contains(audience)) {
                throw new IllegalArgumentException("Invalid access token audience");
            }
            if (claims.getJWTID() == null || claims.getJWTID().isBlank()) {
                throw new IllegalArgumentException("Invalid access token id");
            }
            if (!ACCESS_TOKEN_TYPE.equals(claims.getStringClaim("typ"))) {
                throw new IllegalArgumentException("Invalid access token type");
            }
            Date issuedAt = claims.getIssueTime();
            if (issuedAt == null) {
                throw new IllegalArgumentException("Invalid access token issue time");
            }

            return new JwtAccessTokenClaims(
                    claims.getSubject(),
                    email,
                    role,
                    claims.getIssuer(),
                    audience,
                    claims.getJWTID(),
                    keyId,
                    issuedAt.toInstant(),
                    expiresAt.toInstant());
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid access token", exception);
        }
    }

    private RSAPrivateKey parsePrivateKey(String value) {
        try {
            PrivateKey key = KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(decodeKey(value)));
            return (RSAPrivateKey) key;
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to initialize JWT private key", exception);
        }
    }

    private Map<String, RSAPublicKey> parsePublicKeys(String value) {
        try {
            Map<String, RSAPublicKey> parsed = new LinkedHashMap<>();
            for (String entry : value.split(";")) {
                if (entry.isBlank()) {
                    continue;
                }
                String[] parts = entry.split("=", 2);
                if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
                    throw new IllegalArgumentException("Public keys must use kid=base64;previousKid=base64 format");
                }
                PublicKey key = KeyFactory.getInstance("RSA")
                        .generatePublic(new X509EncodedKeySpec(decodeKey(parts[1])));
                parsed.put(parts[0].trim(), (RSAPublicKey) key);
            }
            if (parsed.isEmpty()) {
                throw new IllegalArgumentException("At least one JWT public key is required");
            }
            return Map.copyOf(parsed);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to initialize JWT public keys", exception);
        }
    }

    private byte[] decodeKey(String value) {
        String normalized = value
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s+", "");
        return Base64.getDecoder().decode(normalized);
    }

    private String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }
}
