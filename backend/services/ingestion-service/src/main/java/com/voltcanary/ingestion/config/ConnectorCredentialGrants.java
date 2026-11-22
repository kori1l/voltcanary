package com.voltcanary.ingestion.config;

import com.voltcanary.ingestion.adapter.http.CredentialProvider;
import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Deployment-owned secret grants. Source configuration can select a reference, never grant access. */
@Component
@ConfigurationProperties(prefix = "app.ingestion.credentials")
public class ConnectorCredentialGrants {

    public record Grant(
        UUID accountId,
        UUID dataSourceId,
        URI endpoint,
        String reference,
        String environmentVariable
    ) {
        public Grant {
            Objects.requireNonNull(accountId, "Credential grant account is required");
            Objects.requireNonNull(dataSourceId, "Credential grant source is required");
            Objects.requireNonNull(endpoint, "Credential grant endpoint is required");
            if (
                endpoint.getHost() == null ||
                endpoint.getUserInfo() != null ||
                !List.of("https", "http", "opc.tcp").contains(endpoint.getScheme()) ||
                reference == null ||
                reference.isBlank() ||
                environmentVariable == null ||
                !environmentVariable.matches("[A-Za-z_][A-Za-z0-9_]*")
            ) {
                throw new IllegalArgumentException("Invalid connector credential grant");
            }
            endpoint = endpoint.normalize();
        }

        public boolean permits(String requested, CredentialProvider.Access access) {
            return (
                access != null &&
                accountId.equals(access.accountId()) &&
                dataSourceId.equals(access.dataSourceId()) &&
                reference.equals(requested) &&
                access.endpoint() != null &&
                endpoint.equals(access.endpoint().normalize())
            );
        }
    }

    private List<Grant> grants = List.of();

    public List<Grant> getGrants() {
        return grants;
    }

    public void setGrants(List<Grant> grants) {
        this.grants = grants == null ? List.of() : List.copyOf(grants);
    }
}
