package com.voltcanary.device.configurable.application;

import com.voltcanary.device.configurable.port.SourceBindingCatalog;
import com.voltcanary.device.configurable.port.SourceCaptureJournal;
import com.voltcanary.device.security.AuthenticatedUser;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclObservationReadiness;
import com.voltcanary.vcl.VclSourceCaptureDescription;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SourceCaptureDescriptions {

    private final SourceBindingCatalog sources;
    private final SourceCaptureJournal journals;

    public VclSourceCaptureDescription describe(AuthenticatedUser actor, UUID id, long revision) {
        var source = sources.describe(actor.id(), id, revision);
        if (source.schemaRevision() != source.currentSchemaRevision()) throw new VclException(
            "BINDING_REVISION_CHANGED",
            ""
        );
        var publication = sources
            .publication(source.accountId(), id)
            .orElseThrow(() -> new VclException("BINDING_METADATA_STALE", ""));
        var value = publication.pin();
        var pin = new VclObservationReadiness.Pin(
            value.bindingId(),
            value.accountId(),
            value.deviceId(),
            value.externalSourceId(),
            value.schemaRevision(),
            value.snapshotId(),
            value.definitionDigest()
        );
        var capture = source.sourceActive()
            ? journals.read(pin)
            : VclObservationReadiness.absent(pin, "DISABLED");
        if (!source.equals(sources.describe(actor.id(), id, revision))) throw new VclException(
            "METADATA_SNAPSHOT_CHANGED",
            ""
        );
        if (!pin.equals(capture.pin())) throw new VclException("OWNER_SNAPSHOT_INVALID", "");
        try {
            return new VclSourceCaptureDescription(source, capture);
        } catch (IllegalArgumentException failure) {
            throw new VclException("OWNER_SNAPSHOT_INVALID", "");
        }
    }
}
