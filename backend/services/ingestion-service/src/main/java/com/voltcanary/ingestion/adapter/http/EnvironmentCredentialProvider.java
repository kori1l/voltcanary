package com.voltcanary.ingestion.adapter.http;

import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class EnvironmentCredentialProvider implements CredentialProvider {

    @Override
    public Optional<String> resolve(String credentialReference) {
        if (credentialReference == null || credentialReference.isBlank()) {
            return Optional.empty();
        }
        String reference = credentialReference.trim();
        if (reference.startsWith("env://")) {
            return Optional.ofNullable(System.getenv(reference.substring("env://".length())));
        }
        if (reference.startsWith("env:")) {
            return Optional.ofNullable(System.getenv(reference.substring("env:".length())));
        }
        if (reference.startsWith("sys:")) {
            return Optional.ofNullable(System.getProperty(reference.substring("sys:".length())));
        }
        return Optional.empty();
    }
}
