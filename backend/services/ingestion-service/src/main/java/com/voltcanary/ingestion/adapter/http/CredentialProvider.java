package com.voltcanary.ingestion.adapter.http;

import java.net.URI;
import java.util.Optional;
import java.util.UUID;

public interface CredentialProvider {
    record Access(UUID accountId, UUID dataSourceId, URI endpoint) {}

    Optional<String> resolve(String credentialReference, Access access);
}
