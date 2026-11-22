package com.voltcanary.device.telemetry;

import com.voltcanary.device.domain.DataSourceProtocol;
import com.voltcanary.device.dto.DataSourceRequest;
import java.util.LinkedHashMap;
import java.util.Map;

/** Distinguishes declared credential references from inline secret-bearing configuration. */
final class SourceCredentialConfiguration {

    private SourceCredentialConfiguration() {}

    static Map<String, Object> nonSecretAdapterConfig(DataSourceRequest request) {
        var config = new LinkedHashMap<String, Object>(
            request.adapterConfig() == null ? Map.of() : request.adapterConfig()
        );
        if (
            request.protocol() == DataSourceProtocol.HTTP_POLLING &&
            config.containsKey("credentialHeaders")
        ) {
            Object value = config.remove("credentialHeaders");
            if (!(value instanceof Map<?, ?> headers) || headers.size() > 32) {
                throw new IllegalArgumentException(
                    "credentialHeaders must be a bounded header-to-reference map"
                );
            }
            headers.forEach((header, reference) -> {
                if (
                    !(header instanceof String name) ||
                    !name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,128}") ||
                    !(reference instanceof String key) ||
                    request.credentialReferences() == null ||
                    !request.credentialReferences().containsKey(key)
                ) {
                    throw new IllegalArgumentException(
                        "Credential headers must name declared credential references"
                    );
                }
            });
        }
        if (
            config.get("headers") instanceof Map<?, ?> headers &&
            headers
                .keySet()
                .stream()
                .anyMatch(key ->
                    String.valueOf(key).matches(
                        "(?i).*(authorization|cookie|api.?key|token|secret).*"
                    )
                )
        ) {
            throw new IllegalArgumentException(
                "Sensitive HTTP headers require credential references"
            );
        }
        return config;
    }
}
