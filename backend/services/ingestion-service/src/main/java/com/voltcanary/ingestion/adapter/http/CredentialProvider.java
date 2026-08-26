package com.voltcanary.ingestion.adapter.http;

import java.util.Optional;

public interface CredentialProvider {

    Optional<String> resolve(String credentialReference);
}
