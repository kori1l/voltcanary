package com.voltcanary.aiagent.provider;

public record EncryptedSecret(String encryptedValue, String keyVersion) {
}
