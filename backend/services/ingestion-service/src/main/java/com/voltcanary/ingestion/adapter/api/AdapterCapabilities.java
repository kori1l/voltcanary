package com.voltcanary.ingestion.adapter.api;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

public record AdapterCapabilities(Set<AdapterCapability> values) {

    public AdapterCapabilities {
        Objects.requireNonNull(values, "values must not be null");
        values = Set.copyOf(values);
    }

    public static AdapterCapabilities of(AdapterCapability first, AdapterCapability... rest) {
        Objects.requireNonNull(first, "first capability must not be null");
        EnumSet<AdapterCapability> capabilities = EnumSet.of(first, rest);
        return new AdapterCapabilities(capabilities);
    }

    public boolean supports(AdapterCapability capability) {
        return values.contains(capability);
    }

    public boolean inbound() {
        return supports(AdapterCapability.INBOUND);
    }

    public boolean polling() {
        return supports(AdapterCapability.POLLING);
    }

    public boolean subscription() {
        return supports(AdapterCapability.SUBSCRIPTION);
    }

    public boolean commands() {
        return supports(AdapterCapability.COMMAND);
    }

    public boolean discovery() {
        return supports(AdapterCapability.DISCOVERY);
    }
}
