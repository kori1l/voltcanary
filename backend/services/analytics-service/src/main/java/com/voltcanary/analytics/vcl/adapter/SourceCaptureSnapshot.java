package com.voltcanary.analytics.vcl.adapter;

import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclMetadata;
import com.voltcanary.vcl.VclObservationReadiness;
import com.voltcanary.vcl.VclSourceCaptureDescription;
import com.voltcanary.vcl.VclSourceCoverage;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** Metadata pin at a captured head, not a sealed row snapshot or an evaluator readiness acknowledgement. */
public final class SourceCaptureSnapshot implements VclMetadata {
    private final VclSourceCaptureDescription original;
    private final String capturedHead;
    private final Supplier<VclSourceCaptureDescription> refresh;
    private final java.util.function.Function<com.voltcanary.vcl.VclObservationRead, com.voltcanary.vcl.VclObservationPage> reader;
    private final java.util.function.Function<UUID, VclSourceCoverage> coverageReader;
    public record Cut(String head, String digest) { }
    public record CoveredCheckpoint(UUID binding, long schemaRevision, Cut cut, VclSourceCoverage proof) { }

    SourceCaptureSnapshot(VclSourceCaptureDescription original, Cut cut, Supplier<VclSourceCaptureDescription> refresh,
            java.util.function.Function<com.voltcanary.vcl.VclObservationRead, com.voltcanary.vcl.VclObservationPage> reader,
            java.util.function.Function<UUID, VclSourceCoverage> coverageReader) {
        this(original, refresh, reader, coverageReader, cut == null ? original.capture().headCheckpoint() : cut.head());
        if (cut != null && (VclObservationReadiness.checkpoint(capturedHead) <= VclObservationReadiness.checkpoint(generationStartCheckpoint())
                || VclObservationReadiness.checkpoint(capturedHead) > VclObservationReadiness.checkpoint(original.capture().headCheckpoint())
                || !cutDigest().equals(cut.digest()))) {
            throw new VclException("CAPTURE_CUT_CHANGED", "");
        }
    }

    private SourceCaptureSnapshot(VclSourceCaptureDescription original, Supplier<VclSourceCaptureDescription> refresh,
            java.util.function.Function<com.voltcanary.vcl.VclObservationRead, com.voltcanary.vcl.VclObservationPage> reader,
            java.util.function.Function<UUID, VclSourceCoverage> coverageReader, String head) {
        this.original = original;
        capturedHead = head;
        this.refresh = refresh;
        this.reader = reader;
        this.coverageReader = coverageReader;
    }
    public void reauthorize() {
        current();
    }

    private VclSourceCaptureDescription current() {
        var current = refresh.get();
        if (!original.source().accessScopeDigest().equals(current.source().accessScopeDigest())) throw new VclException("ACCESS_SCOPE_CHANGED", "");
        if (!original.source().equals(current.source())) throw new VclException("METADATA_SNAPSHOT_CHANGED", "");
        var a = original.capture(); var b = current.capture();
        if (!a.pin().equals(b.pin())) throw new VclException("METADATA_SNAPSHOT_CHANGED", "");
        if (!a.inputGeneration().equals(b.inputGeneration()) || !a.generationStartCheckpoint().equals(b.generationStartCheckpoint())
                || !a.generationStartedAt().equals(b.generationStartedAt()) || !a.profile().equals(b.profile())) {
            throw new VclException("CAPTURE_GENERATION_CHANGED", "");
        }
        if (VclObservationReadiness.checkpoint(b.headCheckpoint()) < VclObservationReadiness.checkpoint(capturedHead)) {
            throw new VclException("CAPTURE_CHECKPOINT_REGRESSED", "");
        }
        return current;
    }
    public UUID inputGeneration() { reauthorize(); return original.capture().inputGeneration(); }
    public String capturedHeadCheckpoint() { reauthorize(); return capturedHead; }

    Cut cut() { return new Cut(capturedHead, cutDigest()); }

    String generationStartCheckpoint() { return original.capture().generationStartCheckpoint(); }
    UUID accountId() { return original.capture().pin().accountId(); }
    UUID snapshotId() { return original.capture().pin().snapshotId(); }

    long retainedObservationCount() {
        var capture = original.capture();
        return VclObservationReadiness.checkpoint(capturedHead)
                - VclObservationReadiness.checkpoint(capture.generationStartCheckpoint());
    }

    String cutDigest() {
        var capture = original.capture();
        var pin = capture.pin();
        return VclCanonical.sha256(String.join("\n", "RULE_JOURNAL_CUT/1", pin.bindingId().toString(),
                pin.snapshotId().toString(), pin.definitionDigest(), original.source().sourceStateDigest(),
                VclCanonical.sha256(pin.externalSourceId()), capture.profile(), capture.inputGeneration().toString(),
                capture.generationStartCheckpoint(), capture.generationStartedAt().toString(), capturedHead));
    }
    /** Read the original captured prefix, retaining conservative quality flags and fresh owner fences. */
    public com.voltcanary.vcl.VclObservationPage readPage(String afterCheckpoint, int limit) {
        return readPage(afterCheckpoint, limit, null, null);
    }

    com.voltcanary.vcl.VclObservationPage readPage(String afterCheckpoint, int limit, Instant from, Instant until) {
        reauthorize();
        com.voltcanary.vcl.VclObservationRead request;
        try {
            if (VclObservationReadiness.checkpoint(afterCheckpoint) < VclObservationReadiness.checkpoint(original.capture().generationStartCheckpoint())) {
                throw new VclException("OBSERVATION_READ_INVALID", "");
            }
            request = new com.voltcanary.vcl.VclObservationRead(original.capture().pin(), original.capture().inputGeneration(),
                    afterCheckpoint, capturedHead, limit, from == null ? null : from.toString(),
                    until == null ? null : until.toString());
        } catch (IllegalArgumentException | NullPointerException invalid) { throw new VclException("OBSERVATION_READ_INVALID", ""); }
        var value = reader.apply(request);
        if (!request.equals(value.request())) throw new VclException("OWNER_SNAPSHOT_INVALID", "");
        value.requireSchema(original.source().definition());
        reauthorize();
        return value;
    }
    public void requireRuntimeReady() { reauthorize(); throw new VclException("SOURCE_RUNTIME_UNAVAILABLE", ""); }

    public VclSourceCoverage coverage(UUID proof, Instant from, Instant until) {
        return coverage(proof, from, until, false);
    }

    VclSourceCoverage coverage(UUID proof, Instant from, Instant until, boolean allowNewerCut) {
        if (proof == null || from == null || until == null || !from.isBefore(until)) throw new VclException("SOURCE_COVERAGE_PROOF_INVALID", "");
        reauthorize();
        var result = coverageReader.apply(proof);
        var capture = original.capture();
        if (!proof.equals(result.proofId()) || !capture.pin().snapshotId().equals(result.snapshotId())) {
            throw new VclException("OWNER_SNAPSHOT_INVALID", "");
        }
        var latest = current();
        if (!"COVERED".equals(result.coverage())) return result;
        if (!capture.inputGeneration().equals(result.inputGeneration())) throw new VclException("CAPTURE_GENERATION_CHANGED", "");
        String limit = allowNewerCut ? latest.capture().headCheckpoint() : capturedHead;
        if (VclObservationReadiness.checkpoint(result.requiredCheckpoint()) > VclObservationReadiness.checkpoint(limit)
                || result.evidence().from().isAfter(from) || result.evidence().until().isBefore(until)) {
            return new VclSourceCoverage(proof, null, result.snapshotId(), "UNKNOWN", "PROOF_OUTSIDE_CAPTURED_CUT", null, null);
        }
        return result;
    }

    SourceCaptureSnapshot forFinalization(VclSourceCoverage proof) {
        var latest = current();
        var required = VclObservationReadiness.checkpoint(proof.requiredCheckpoint());
        if (!proof.qualifiesEventTime() || !latest.capture().inputGeneration().equals(proof.inputGeneration())
                || !latest.capture().pin().snapshotId().equals(proof.snapshotId())
                || required > VclObservationReadiness.checkpoint(latest.capture().headCheckpoint())) {
            throw new VclException("WINDOW_COVERAGE_CHANGED", "");
        }
        if (required <= VclObservationReadiness.checkpoint(capturedHead)) return this;
        // Use exactly the proof checkpoint, even when the live journal has grown further.
        var pinned = new SourceCaptureSnapshot(latest, refresh, reader, coverageReader, proof.requiredCheckpoint());
        pinned.reauthorize();
        return pinned;
    }

    SourceCaptureSnapshot atCurrentHead() {
        var latest = current();
        return new SourceCaptureSnapshot(latest, refresh, reader, coverageReader, latest.capture().headCheckpoint());
    }

    Instant lastRecordedAt() {
        long head = VclObservationReadiness.checkpoint(capturedHead);
        if (head == VclObservationReadiness.checkpoint(generationStartCheckpoint())) {
            reauthorize();
            return original.capture().generationStartedAt();
        }
        var page = readPage(Long.toString(head - 1), 1);
        var rows = page.observations();
        if (rows.size() != 1 || !capturedHead.equals(page.nextCheckpoint()) || page.hasMore()) {
            throw new VclException("CAPTURE_CHECKPOINT_CHANGED", "");
        }
        return Instant.parse(rows.getFirst().path("recordedAt").textValue());
    }

    SourceCaptureSnapshot atCheckpoint(String checkpoint) {
        var latest = current();
        long position = VclObservationReadiness.checkpoint(checkpoint);
        if (position <= VclObservationReadiness.checkpoint(generationStartCheckpoint())
                || position > VclObservationReadiness.checkpoint(latest.capture().headCheckpoint())) {
            throw new VclException("CAPTURE_CUT_CHANGED", "");
        }
        return new SourceCaptureSnapshot(latest, refresh, reader, coverageReader, checkpoint);
    }

    /** Pin an actual owner-attested interval ending at the requested start boundary. */
    CoveredCheckpoint coveredCheckpoint(UUID proof, Instant from, Instant boundary) {
        var covered = coverage(proof, from, boundary);
        if (!"COVERED".equals(covered.coverage())
                || VclObservationReadiness.checkpoint(covered.requiredCheckpoint())
                        <= VclObservationReadiness.checkpoint(generationStartCheckpoint())) {
            throw new VclException("SOURCE_START_CHECKPOINT_UNAVAILABLE", "");
        }
        return new CoveredCheckpoint(original.source().id(), original.source().schemaRevision(), cut(), covered);
    }
    public void requireNegativeEvidence() { reauthorize(); throw new VclException("NEGATIVE_EVIDENCE_UNAVAILABLE", ""); }
    @Override public String accessScopeDigest() { reauthorize(); return original.source().accessScopeDigest(); }
    @Override public Optional<Snapshot> dataset(UUID id, long revision) { return Optional.empty(); }
    @Override public Optional<Snapshot> binding(UUID id, long revision) {
        if (!original.source().id().equals(id) || original.source().schemaRevision() != revision) return Optional.empty();
        reauthorize();
        return Optional.of(new Snapshot(id, revision, original.source().definition()));
    }
    @Override public boolean permits(Resource kind, UUID id) { return false; }
    @Override public String toString() { return "SourceCaptureSnapshot[bindingId=" + original.source().id() + "]"; }
}
