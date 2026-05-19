package com.voltcanary.ingestion.source;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.stereotype.Component;

@Component
public class SourceTokenValidator {

    public String prefix(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("Source token is required");
        }
        return token.substring(0, Math.min(16, token.length()));
    }

    public boolean matches(String rawToken, String expectedHash) {
        return expectedHash != null && MessageDigest.isEqual(
                sha256Hex(rawToken).getBytes(StandardCharsets.UTF_8),
                expectedHash.getBytes(StandardCharsets.UTF_8));
    }

    private String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to hash source token", exception);
        }
    }
}
