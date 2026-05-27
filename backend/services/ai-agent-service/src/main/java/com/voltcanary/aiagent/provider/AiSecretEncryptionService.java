package com.voltcanary.aiagent.provider;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class AiSecretEncryptionService {
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final String encryptionKey;
    private final String keyVersion;
    private final SecureRandom secureRandom = new SecureRandom();

    public AiSecretEncryptionService(
            @Value("${app.ai.secrets.encryption-key:}") String encryptionKey,
            @Value("${app.ai.secrets.key-version:v1}") String keyVersion) {
        this.encryptionKey = encryptionKey == null ? "" : encryptionKey;
        this.keyVersion = keyVersion == null || keyVersion.isBlank() ? "v1" : keyVersion;
    }

    public EncryptedSecret encrypt(String plainText) {
        requireKey();
        try {
            byte[] iv = new byte[IV_BYTES];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keySpec(), new GCMParameterSpec(TAG_BITS, iv));
            byte[] cipherText = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            ByteBuffer buffer = ByteBuffer.allocate(iv.length + cipherText.length);
            buffer.put(iv).put(cipherText);
            return new EncryptedSecret(Base64.getEncoder().encodeToString(buffer.array()), keyVersion);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to encrypt AI provider secret", exception);
        }
    }

    public String decrypt(String encryptedValue) {
        requireKey();
        try {
            byte[] payload = Base64.getDecoder().decode(encryptedValue);
            ByteBuffer buffer = ByteBuffer.wrap(payload);
            byte[] iv = new byte[IV_BYTES];
            buffer.get(iv);
            byte[] cipherText = new byte[buffer.remaining()];
            buffer.get(cipherText);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keySpec(), new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to decrypt AI provider secret", exception);
        }
    }

    public String mask(String plainText) {
        if (plainText == null || plainText.isBlank()) {
            return "****";
        }
        String trimmed = plainText.trim();
        if (trimmed.length() <= 8) {
            return "****";
        }
        int prefix = Math.min(4, trimmed.length() / 3);
        int suffix = Math.min(4, trimmed.length() - prefix);
        return trimmed.substring(0, prefix) + "-..." + trimmed.substring(trimmed.length() - suffix);
    }

    public String keyVersion() {
        return keyVersion;
    }

    private SecretKeySpec keySpec() throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(encryptionKey.getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(digest, "AES");
    }

    private void requireKey() {
        if (encryptionKey.isBlank()) {
            throw new IllegalStateException("AI_AGENT_SECRET_ENCRYPTION_KEY is required to store model provider secrets");
        }
    }
}
