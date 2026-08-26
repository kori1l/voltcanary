package com.voltcanary.ingestion.adapter.api;

import java.util.Objects;

public record AdapterManifest(
        String adapterId,
        AdapterType adapterType,
        String displayName,
        TransportType transport,
        ProtocolType protocol,
        AdapterCapabilities capabilities,
        String configSchemaVersion) {

    public AdapterManifest {
        adapterId = requireText(adapterId, "adapterId");
        displayName = requireText(displayName, "displayName");
        configSchemaVersion = requireText(configSchemaVersion, "configSchemaVersion");
        Objects.requireNonNull(adapterType, "adapterType must not be null");
        Objects.requireNonNull(transport, "transport must not be null");
        Objects.requireNonNull(protocol, "protocol must not be null");
        Objects.requireNonNull(capabilities, "capabilities must not be null");
        if (capabilities.values().isEmpty()) {
            throw new IllegalArgumentException("adapter capabilities must not be empty");
        }
    }

    public boolean supports(AdapterCapability capability) {
        return capabilities.supports(capability);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
