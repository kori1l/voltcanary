package com.voltcanary.common.events.telemetry;

import java.util.Objects;
import java.util.UUID;

/** Server-resolved schema identity only; not authority, input generation or coverage/readiness. */
public record SourceBindingSchemaPin(
    UUID bindingId,
    UUID accountId,
    UUID deviceId,
    String externalSourceId,
    long schemaRevision,
    UUID snapshotId,
    String definitionDigest
) {
    public SourceBindingSchemaPin {
        Objects.requireNonNull(bindingId);
        Objects.requireNonNull(accountId);
        Objects.requireNonNull(deviceId);
        Objects.requireNonNull(snapshotId);
        if (
            externalSourceId == null ||
            externalSourceId.isBlank() ||
            externalSourceId.length() > 512 ||
            schemaRevision < 1 ||
            schemaRevision > 9_007_199_254_740_991L ||
            definitionDigest == null ||
            !definitionDigest.matches("[0-9a-f]{64}")
        ) throw new IllegalArgumentException("Invalid binding schema pin");
    }

    @Override
    public String toString() {
        return (
            "SourceBindingSchemaPin[bindingId=" +
            bindingId +
            ", schemaRevision=" +
            schemaRevision +
            "]"
        );
    }
}
