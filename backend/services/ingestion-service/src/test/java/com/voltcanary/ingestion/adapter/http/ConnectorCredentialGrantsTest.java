package com.voltcanary.ingestion.adapter.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.voltcanary.ingestion.config.ConnectorCredentialGrants;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConnectorCredentialGrantsTest {

    @Test
    void requiresAnExactDeploymentGrantForOwnerSourceDestinationAndReference() {
        var account = UUID.randomUUID();
        var source = UUID.randomUUID();
        var endpoint = URI.create("https://sensor.example/reading");
        var access = new CredentialProvider.Access(account, source, endpoint);
        var properties = new ConnectorCredentialGrants();
        var provider = new EnvironmentCredentialProvider(properties);
        String variable = System.getenv()
            .keySet()
            .stream()
            .filter(key -> key.matches("[A-Za-z_][A-Za-z0-9_]*"))
            .findFirst()
            .orElseThrow();
        assertThat(provider.resolve("credential:sensor", access)).isEmpty();
        properties.setGrants(
            List.of(
                new ConnectorCredentialGrants.Grant(
                    account,
                    source,
                    endpoint,
                    "credential:sensor",
                    variable
                )
            )
        );
        assertThat(provider.resolve("credential:sensor", access)).isPresent();
        assertThat(
            provider.resolve(
                "credential:sensor",
                new CredentialProvider.Access(UUID.randomUUID(), source, endpoint)
            )
        ).isEmpty();
        assertThat(
            provider.resolve(
                "credential:sensor",
                new CredentialProvider.Access(account, UUID.randomUUID(), endpoint)
            )
        ).isEmpty();
        assertThat(
            provider.resolve(
                "credential:sensor",
                new CredentialProvider.Access(
                    account,
                    source,
                    URI.create("https://attacker.example/reading")
                )
            )
        ).isEmpty();
        assertThat(
            provider.resolve(
                "credential:sensor",
                new CredentialProvider.Access(
                    account,
                    source,
                    URI.create("https://sensor.example/other")
                )
            )
        ).isEmpty();
        assertThat(provider.resolve("credential:sensor", null)).isEmpty();
        assertThat(provider.resolve("env:" + variable, access)).isEmpty();
        assertThat(provider.resolve("sys:java.home", access)).isEmpty();
    }
}
