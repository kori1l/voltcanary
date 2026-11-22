package com.voltcanary.ingestion.adapter.http;

import com.voltcanary.ingestion.config.ConnectorCredentialGrants;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class EnvironmentCredentialProvider implements CredentialProvider {

    private final ConnectorCredentialGrants grants;

    public EnvironmentCredentialProvider(ConnectorCredentialGrants grants) {
        this.grants = grants;
    }

    @Override
    public Optional<String> resolve(String credentialReference, Access access) {
        if (credentialReference == null || credentialReference.isBlank()) {
            return Optional.empty();
        }
        var variables = grants
            .getGrants()
            .stream()
            .filter(grant -> grant.permits(credentialReference, access))
            .map(ConnectorCredentialGrants.Grant::environmentVariable)
            .distinct()
            .toList();
        return variables.size() == 1
            ? Optional.ofNullable(System.getenv(variables.getFirst()))
            : Optional.empty();
    }
}
