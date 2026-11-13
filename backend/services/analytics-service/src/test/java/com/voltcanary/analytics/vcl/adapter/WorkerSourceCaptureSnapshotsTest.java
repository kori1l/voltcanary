package com.voltcanary.analytics.vcl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclObservationProfile;
import com.voltcanary.vcl.VclObservationReadiness;
import com.voltcanary.vcl.VclObservationPage;
import com.voltcanary.vcl.VclSourceCoverage;
import com.voltcanary.vcl.VclSourceBindingDescription;
import com.voltcanary.vcl.VclSourceBindingProfile;
import com.voltcanary.vcl.VclSourceCaptureDescription;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkerSourceCaptureSnapshotsTest {
    private final UUID account = UUID.randomUUID(), actor = UUID.randomUUID(), epoch = UUID.randomUUID();
    private final UUID binding = UUID.randomUUID(), device = UUID.randomUUID(), schema = UUID.randomUUID();
    private final UUID generation = UUID.randomUUID();
    private final WorkerSourceCaptureReads reads = mock(WorkerSourceCaptureReads.class);

    @Test void restoresExactCutWithoutUserBearerAndRenewsMachineAuthority() {
        var original = fixture();
        when(reads.capture(account, epoch, binding, 1)).thenReturn(original);
        var snapshots = new WorkerSourceCaptureSnapshots(reads);
        var first = snapshots.capture(account, epoch, binding, 1, null);
        var retained = snapshots.capture(account, epoch, binding, 1, first.cut());
        assertEquals(generation, retained.inputGeneration());
        assertEquals(first.cut(), retained.cut());
        assertEquals("5", retained.capturedHeadCheckpoint());
    }

    @Test void rejectsChangedCutAndRevokedOwnerOnRestoredRead() {
        var original = fixture();
        when(reads.capture(account, epoch, binding, 1)).thenReturn(original);
        var snapshots = new WorkerSourceCaptureSnapshots(reads);
        assertEquals("CAPTURE_CUT_CHANGED", assertThrows(VclException.class, () -> snapshots.capture(account, epoch,
                binding, 1, new SourceCaptureSnapshot.Cut("5", "0".repeat(64)))).code());
        var retained = snapshots.capture(account, epoch, binding, 1, null);
        when(reads.capture(account, epoch, binding, 1)).thenThrow(new VclException("AUTOMATION_GRANT_INACTIVE", ""));
        assertEquals("AUTOMATION_GRANT_INACTIVE", assertThrows(VclException.class, retained::inputGeneration).code());
    }

    @Test void routesPageAndProofThroughScopedMachineOwner() {
        var original = fixture();
        when(reads.capture(account, epoch, binding, 1)).thenReturn(original);
        when(reads.observations(org.mockito.ArgumentMatchers.eq(account), org.mockito.ArgumentMatchers.eq(epoch),
                org.mockito.ArgumentMatchers.eq(binding), org.mockito.ArgumentMatchers.eq(1L), any()))
                .thenAnswer(call -> {
                    var request = (com.voltcanary.vcl.VclObservationRead) call.getArgument(4);
                    var page = mock(VclObservationPage.class);
                    when(page.request()).thenReturn(request);
                    return page;
                });
        UUID proof = UUID.randomUUID();
        when(reads.coverage(account, epoch, binding, 1, proof, original.capture().pin()))
                .thenReturn(new VclSourceCoverage(proof, null, schema, "UNKNOWN", "PROOF_UNAVAILABLE", null, null));
        var retained = new WorkerSourceCaptureSnapshots(reads).capture(account, epoch, binding, 1, null);
        retained.readPage("0", 8);
        assertEquals("UNKNOWN", retained.coverage(proof, Instant.parse("2026-11-03T00:00:00Z"),
                Instant.parse("2026-11-03T01:00:00Z")).coverage());
        verify(reads).coverage(account, epoch, binding, 1, proof, original.capture().pin());
    }

    private VclSourceCaptureDescription fixture() {
        var definition = new ObjectMapper().createObjectNode().put("id", binding.toString()).put("schemaRevision", 1)
                .put("profile", VclSourceBindingProfile.VERSION).put("configurationDigest", "a".repeat(64));
        definition.putArray("fields").addObject().put("id", UUID.randomUUID().toString()).put("type", "BOOLEAN")
                .put("label", "Switch").put("nullable", true);
        String digest = VclCanonical.sha256(VclCanonical.encode(definition));
        var source = new VclSourceBindingDescription(binding, account, actor, device, 1, 1, schema, definition, digest,
                "a".repeat(64), "a".repeat(64), true, false, VclSourceBindingProfile.capabilities());
        var pin = new VclObservationReadiness.Pin(binding, account, device, "source", 1, schema, digest);
        var capture = new VclObservationReadiness(pin, VclObservationProfile.VERSION, "READY", generation, "0", "5",
                Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MICROS), true, false, false,
                "UNKNOWN", "UNKNOWN", "UNQUALIFIED", null);
        return new VclSourceCaptureDescription(source, capture);
    }
}
