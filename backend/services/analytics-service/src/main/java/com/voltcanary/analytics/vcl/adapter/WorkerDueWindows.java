package com.voltcanary.analytics.vcl.adapter;

import com.voltcanary.analytics.vcl.KeyedStateStore;
import com.voltcanary.vcl.VclCompiler;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclParser;
import com.voltcanary.vcl.VclRegistry;
import com.voltcanary.vcl.VclUnits;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;

/** Opt-in autonomous drain of diagnostic WINDOW_CLOSE measures under fresh machine authority. */
@Slf4j
public final class WorkerDueWindows {
    private static final int MAX_TIMER_CURSORS = 128;
    private record DeploymentKey(UUID account, UUID rule, UUID epoch, KeyedStateStore.TimerKind kind) {}

    private final WorkerDeploymentAdmission admission;
    private final WorkerRuleCompilationSnapshots recovery;
    private final KeyedStateStore store;
    private final VclCompiler compiler;
    private final UUID holder = UUID.randomUUID();
    private final LinkedHashMap<DeploymentKey, KeyedStateStore.DueTimer> timerCursors =
            new LinkedHashMap<>(16, 0.75f, true);
    private int nextPageOffset;
    private final LinkedHashMap<UUID, Integer> sustainedOffsets = new LinkedHashMap<>(16, 0.75f, true);

    public WorkerDueWindows(DataSource source, WorkerDeploymentAdmission admission,
            WorkerRuleCompilationSnapshots recovery) {
        this(admission, recovery, new JdbcKeyedStateStore(source),
                new VclCompiler(new VclRegistry(new VclParser()), VclUnits.standard()));
    }

    WorkerDueWindows(WorkerDeploymentAdmission admission, WorkerRuleCompilationSnapshots recovery,
            KeyedStateStore store, VclCompiler compiler) {
        this.admission = admission;
        this.recovery = recovery;
        this.store = store;
        this.compiler = compiler;
    }

    @Scheduled(fixedDelayString = "${app.vcl.worker.delay:5s}")
    public void poll() {
        try { drain(32, 32); }
        catch (RuntimeException unavailable) { log.warn("VCL diagnostic worker scan failed: {}", reason(unavailable)); }
    }

    /** Owner failures leave the corresponding due timer pending for a later scan. */
    public synchronized List<KeyedStateStore.OutputStatus> drain(int deploymentLimit, int timerLimit) {
        var results = new ArrayList<KeyedStateStore.OutputStatus>();
        int offset = nextPageOffset;
        var candidates = admission.candidates(deploymentLimit, offset);
        if (candidates.isEmpty() && offset > 0) {
            offset = 0;
            candidates = admission.candidates(deploymentLimit, 0);
        }
        nextPageOffset = candidates.size() < deploymentLimit || offset + candidates.size() > 1_000_000
                ? 0 : offset + candidates.size();
        for (var candidate : candidates) {
            try {
                admission.inspect(candidate);
                var timers = page(candidate, KeyedStateStore.TimerKind.WINDOW_CLOSE, timerLimit);
                for (var due : timers) {
                    try {
                        if (store instanceof JdbcKeyedStateStore jdbc
                                && IntervalWindowSchedule.handles(due.scheduled().timer().payload())) {
                            recovery.finishIntervalWindow(candidate, jdbc, due, compiler, holder);
                            continue;
                        }
                        var restored = recovery.restoreWindow(candidate, store, due.key(), due.pinsDigest(), compiler);
                        results.add(restored.consumeDiscovered(due, holder));
                    } catch (RuntimeException unavailable) {
                        log.warn("VCL diagnostic worker left a due timer pending: {}", reason(unavailable));
                    }
                }
                if (candidate.start() != null && store instanceof JdbcKeyedStateStore jdbc) {
                    int sustainedOffset = sustainedOffsets.getOrDefault(candidate.epoch(), 0);
                    var measures = jdbc.pendingSustained(candidate, timerLimit, sustainedOffset);
                    if (measures.size() < timerLimit || sustainedOffset + timerLimit > 1_000_000) {
                        sustainedOffsets.remove(candidate.epoch());
                    } else {
                        sustainedOffsets.put(candidate.epoch(), sustainedOffset + timerLimit);
                        if (sustainedOffsets.size() > MAX_TIMER_CURSORS) sustainedOffsets.pollFirstEntry();
                    }
                    for (var pending : measures) {
                        try {
                            recovery.finishSustained(candidate, jdbc, pending, compiler, holder);
                        } catch (RuntimeException unavailable) {
                            log.warn("VCL sustained measure remains pending: {}", reason(unavailable));
                        }
                    }
                    for (var due : page(candidate, KeyedStateStore.TimerKind.SESSION_TIMEOUT, timerLimit)) {
                        try {
                            recovery.timeoutSession(candidate, jdbc, due, compiler, holder);
                        } catch (RuntimeException unavailable) {
                            log.warn("VCL session timeout remains pending: {}", reason(unavailable));
                        }
                    }
                    for (var due : page(candidate, KeyedStateStore.TimerKind.WAIT_TIMEOUT, timerLimit)) {
                        try {
                            recovery.finishSessionAbsence(candidate, jdbc, due, compiler, holder);
                        } catch (RuntimeException unavailable) {
                            log.warn("VCL session absence remains pending: {}", reason(unavailable));
                        }
                    }
                }
            } catch (RuntimeException unavailable) {
                log.warn("VCL diagnostic worker left a deployment pending: {}", reason(unavailable));
            }
        }
        return List.copyOf(results);
    }

    private List<KeyedStateStore.DueTimer> page(JdbcRuleDeployments.Deployment candidate,
            KeyedStateStore.TimerKind kind, int limit) {
        var scope = new DeploymentKey(candidate.account(), candidate.rule(), candidate.epoch(), kind);
        var timers = store.dueTimers(scope.account(), scope.rule(), scope.epoch(), kind, timerCursors.get(scope), limit);
        if (timers.size() < limit) {
            timerCursors.remove(scope);
        } else {
            timerCursors.put(scope, timers.getLast());
            if (timerCursors.size() > MAX_TIMER_CURSORS) timerCursors.pollFirstEntry();
        }
        return timers;
    }

    private static String reason(RuntimeException failure) {
        return failure instanceof VclException vcl ? vcl.code() : failure.getClass().getSimpleName();
    }
}
