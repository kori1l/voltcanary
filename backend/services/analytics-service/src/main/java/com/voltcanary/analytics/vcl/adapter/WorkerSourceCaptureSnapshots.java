package com.voltcanary.analytics.vcl.adapter;

import com.voltcanary.vcl.VclException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Restores a retained journal cut through scoped machine owner reads. */
@Component
@RequiredArgsConstructor
public final class WorkerSourceCaptureSnapshots {

    private final WorkerSourceCaptureReads reads;

    public SourceCaptureSnapshot capture(
        UUID account,
        UUID epoch,
        UUID binding,
        long revision,
        SourceCaptureSnapshot.Cut cut
    ) {
        if (account == null || epoch == null || binding == null || revision < 1) {
            throw new VclException("WORKER_TARGET_INVALID", "");
        }
        var initial = reads.capture(account, epoch, binding, revision);
        if (!initial.capture().journalReady()) throw new VclException(
            "SOURCE_CAPTURE_UNAVAILABLE",
            ""
        );
        return new SourceCaptureSnapshot(
            initial,
            cut,
            () -> reads.capture(account, epoch, binding, revision),
            request -> reads.observations(account, epoch, binding, revision, request),
            proof ->
                reads.coverage(account, epoch, binding, revision, proof, initial.capture().pin())
        );
    }

    UUID findCoverage(
        UUID account,
        UUID epoch,
        UUID binding,
        long revision,
        UUID generation,
        java.time.Instant from,
        java.time.Instant until
    ) {
        return reads.findCoverage(account, epoch, binding, revision, generation, from, until);
    }

    UUID findCoverage(
        UUID account,
        UUID epoch,
        UUID binding,
        long revision,
        UUID generation,
        java.time.Instant from,
        java.time.Instant until,
        long minimumCheckpoint
    ) {
        return reads.findCoverage(
            account,
            epoch,
            binding,
            revision,
            generation,
            from,
            until,
            minimumCheckpoint
        );
    }
}
