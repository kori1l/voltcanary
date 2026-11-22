package com.voltcanary.vcl;

import java.util.Set;

/** Source-authorized wrapper around actual telemetry journal state. Schema and input generations stay distinct. */
public record VclSourceCaptureDescription(
    VclSourceBindingDescription source,
    VclObservationReadiness capture
) {
    public VclSourceCaptureDescription {
        java.util.Objects.requireNonNull(source);
        java.util.Objects.requireNonNull(capture);
        var pin = capture.pin();
        if (
            !source.id().equals(pin.bindingId()) ||
            !source.accountId().equals(pin.accountId()) ||
            !source.deviceId().equals(pin.deviceId()) ||
            source.schemaRevision() != pin.schemaRevision() ||
            source.currentSchemaRevision() != source.schemaRevision() ||
            !source.snapshotId().equals(pin.snapshotId()) ||
            !source.definitionDigest().equals(pin.definitionDigest()) ||
            (!source.sourceActive() && capture.journalReady())
        ) throw new IllegalArgumentException();
    }

    public static VclSourceCaptureDescription parse(byte[] bytes) {
        try {
            var value = new VclParser().readJson(bytes);
            var names = new java.util.HashSet<String>();
            value.fieldNames().forEachRemaining(names::add);
            if (
                !value.isObject() || !names.equals(Set.of("source", "capture"))
            ) throw new IllegalArgumentException();
            return new VclSourceCaptureDescription(
                VclSourceBindingDescription.parse(
                    value
                        .path("source")
                        .toString()
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)
                ),
                VclObservationReadiness.parse(
                    value
                        .path("capture")
                        .toString()
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)
                )
            );
        } catch (RuntimeException failure) {
            throw new VclException("OWNER_SNAPSHOT_INVALID", "");
        }
    }

    @Override
    public String toString() {
        return (
            "VclSourceCaptureDescription[bindingId=" +
            source.id() +
            ", state=" +
            capture.state() +
            "]"
        );
    }
}
