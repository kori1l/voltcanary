package com.voltcanary.aiagent.domain;

public record EncryptedSecret(String encryptedValue, String keyVersion) {
}
