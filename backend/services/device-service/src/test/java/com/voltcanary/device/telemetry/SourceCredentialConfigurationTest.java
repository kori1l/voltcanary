package com.voltcanary.device.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.voltcanary.device.domain.DataSourceProtocol;
import com.voltcanary.device.domain.DataSourceType;
import com.voltcanary.device.dto.DataSourceRequest;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SourceCredentialConfigurationTest {

    private DataSourceRequest request(Map<String, Object> config) {
        return new DataSourceRequest(
            DataSourceType.values()[0],
            DataSourceProtocol.HTTP_POLLING,
            "source",
            Map.of(),
            config,
            Map.of("sensor", "credential:sensor"),
            null
        );
    }

    @Test
    void allowsOnlyDeclaredReferencesAndKeepsTheStoredConfigurationIntact() {
        var config = Map.<String, Object>of(
            "url",
            "https://sensor.example/reading",
            "credentialHeaders",
            Map.of("Authorization", "sensor")
        );
        assertThat(
            SourceCredentialConfiguration.nonSecretAdapterConfig(request(config))
        ).containsOnlyKeys("url");
        assertThat(config).containsKey("credentialHeaders");
        assertThatThrownBy(() ->
            SourceCredentialConfiguration.nonSecretAdapterConfig(
                request(Map.of("credentialHeaders", Map.of("Authorization", "undeclared")))
            )
        ).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
            SourceCredentialConfiguration.nonSecretAdapterConfig(
                request(Map.of("headers", Map.of("Authorization", "inline-secret")))
            )
        ).isInstanceOf(IllegalArgumentException.class);
    }
}
