package com.voltcanary.identity.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.identity.config.IdentityAuthProperties;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Recovery credentials are recoverable for delivery only from encrypted, short-lived outbox rows. */
@Service
public class RecoveryMailCipher {

    private final String configuredKey;
    private final ObjectMapper mapper;
    private final SecureRandom random = new SecureRandom();

    public RecoveryMailCipher(IdentityAuthProperties properties, ObjectMapper mapper) {
        configuredKey = properties.getMailEncryptionKey();
        this.mapper = mapper;
    }

    public void requireConfigured() {
        key();
    }

    private SecretKeySpec key() {
        try {
            if (
                configuredKey == null || configuredKey.isBlank()
            ) throw new IllegalArgumentException();
            byte[] material = Base64.getDecoder().decode(configuredKey);
            if (material.length != 32) throw new IllegalArgumentException();
            return new SecretKeySpec(material, "AES");
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Account recovery encryption is unavailable"
            );
        }
    }

    public String encrypt(RecoveryMail.Message message) {
        var key = key();
        try {
            byte[] iv = new byte[12];
            random.nextBytes(iv);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] payload = cipher.doFinal(mapper.writeValueAsBytes(message));
            return Base64.getEncoder().encodeToString(
                ByteBuffer.allocate(iv.length + payload.length)
                    .put(iv)
                    .put(payload)
                    .array()
            );
        } catch (Exception failure) {
            throw new IllegalStateException("Could not encrypt recovery delivery", failure);
        }
    }

    public RecoveryMail.Message decrypt(String payload) {
        var key = key();
        try {
            var buffer = ByteBuffer.wrap(Base64.getDecoder().decode(payload));
            byte[] iv = new byte[12];
            buffer.get(iv);
            byte[] encrypted = new byte[buffer.remaining()];
            buffer.get(encrypted);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            return mapper.readValue(cipher.doFinal(encrypted), RecoveryMail.Message.class);
        } catch (Exception failure) {
            throw new IllegalStateException("Could not decrypt recovery delivery", failure);
        }
    }
}
