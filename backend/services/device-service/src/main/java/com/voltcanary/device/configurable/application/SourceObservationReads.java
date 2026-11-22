package com.voltcanary.device.configurable.application;

import com.voltcanary.device.configurable.port.SourceBindingCatalog;
import com.voltcanary.device.configurable.port.SourceCaptureJournal;
import com.voltcanary.device.security.AuthenticatedUser;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclObservationPage;
import com.voltcanary.vcl.VclObservationRead;
import com.voltcanary.vcl.VclObservationReadiness;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Signed owner reads renew actual account/source authority before and after private telemetry I/O. */
@Service
@RequiredArgsConstructor
public class SourceObservationReads {

    private final SourceBindingCatalog sources;
    private final SourceCaptureJournal journals;

    public VclObservationPage read(AuthenticatedUser actor, UUID id, VclObservationRead request) {
        var source = verify(actor, id, request.pin());
        var page = journals.page(request);
        if (
            !source.equals(sources.describe(actor.id(), id, request.pin().schemaRevision()))
        ) throw new VclException("METADATA_SNAPSHOT_CHANGED", "");
        if (!request.equals(page.request())) throw new VclException("OWNER_SNAPSHOT_INVALID", "");
        page.requireSchema(source.definition());
        return page;
    }

    public com.voltcanary.vcl.VclSourceCoverage coverage(
        AuthenticatedUser actor,
        UUID id,
        VclObservationReadiness.Pin pin,
        UUID proof
    ) {
        var source = verify(actor, id, pin);
        var result = journals.coverage(pin, proof);
        if (
            !source.equals(sources.describe(actor.id(), id, pin.schemaRevision()))
        ) throw new VclException("METADATA_SNAPSHOT_CHANGED", "");
        if (!proof.equals(result.proofId()) || !pin.snapshotId().equals(result.snapshotId())) {
            throw new VclException("OWNER_SNAPSHOT_INVALID", "");
        }
        return result;
    }

    public UUID findQualifiedCoverage(
        AuthenticatedUser actor,
        UUID id,
        com.voltcanary.vcl.VclCoverageSearch request
    ) {
        var pin = request.pin();
        var source = verify(actor, id, pin);
        UUID proof = journals.findQualifiedCoverage(request);
        if (!source.equals(sources.describe(actor.id(), id, pin.schemaRevision()))) {
            throw new VclException("METADATA_SNAPSHOT_CHANGED", "");
        }
        return proof;
    }

    private com.voltcanary.vcl.VclSourceBindingDescription verify(
        AuthenticatedUser actor,
        UUID id,
        VclObservationReadiness.Pin requested
    ) {
        if (!id.equals(requested.bindingId())) throw new VclException(
            "OBSERVATION_READ_INVALID",
            ""
        );
        var source = sources.describe(actor.id(), id, requested.schemaRevision());
        if (!source.sourceActive()) throw new VclException("SOURCE_NOT_ACTIVE", "");
        if (source.currentSchemaRevision() != source.schemaRevision()) throw new VclException(
            "BINDING_REVISION_CHANGED",
            ""
        );
        var publication = sources
            .publication(source.accountId(), id)
            .orElseThrow(() -> new VclException("BINDING_METADATA_STALE", ""));
        var value = publication.pin();
        var actual = new VclObservationReadiness.Pin(
            value.bindingId(),
            value.accountId(),
            value.deviceId(),
            value.externalSourceId(),
            value.schemaRevision(),
            value.snapshotId(),
            value.definitionDigest()
        );
        if (!actual.equals(requested)) throw new VclException("METADATA_SNAPSHOT_CHANGED", "");
        return source;
    }
}
