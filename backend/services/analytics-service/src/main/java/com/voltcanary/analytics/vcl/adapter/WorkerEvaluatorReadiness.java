package com.voltcanary.analytics.vcl.adapter;

import com.voltcanary.vcl.VclCompiler;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclParser;
import com.voltcanary.vcl.VclRegistry;
import com.voltcanary.vcl.VclUnits;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;

/** Bounded preparation of pending evaluator ACKs under machine authority. */
@Slf4j
final class WorkerEvaluatorReadiness {

    private static final int PAGE_SIZE = 8;
    private final WorkerStartCheckpoints checkpoints;
    private final VclCompiler compiler = new VclCompiler(
        new VclRegistry(new VclParser()),
        VclUnits.standard()
    );
    private int offset;

    WorkerEvaluatorReadiness(WorkerStartCheckpoints checkpoints) {
        this.checkpoints = checkpoints;
    }

    @Scheduled(fixedDelayString = "${app.vcl.worker.delay:5s}")
    public void poll() {
        var page = checkpoints.pending(PAGE_SIZE, offset);
        offset = page.size() < PAGE_SIZE || offset + PAGE_SIZE > 1_000_000 ? 0 : offset + PAGE_SIZE;
        for (var candidate : page) {
            try {
                checkpoints.acknowledge(candidate, compiler);
            } catch (VclException unavailable) {
                log.debug("VCL evaluator readiness remains pending: {}", unavailable.code());
            }
        }
    }
}
