package com.voltcanary.analytics.vcl.adapter;

import com.voltcanary.vcl.VclCompiler;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclParser;
import com.voltcanary.vcl.VclRegistry;
import com.voltcanary.vcl.VclUnits;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** Opens windows from a bounded journal page per active reviewed epoch. */
final class WorkerJournalWindowOpening {
    private static final Logger LOG = LoggerFactory.getLogger(WorkerJournalWindowOpening.class);
    private static final int PAGE_SIZE = 8;
    private final JdbcKeyedStateStore store;
    private final WorkerRuleCompilationSnapshots snapshots;
    private final VclCompiler compiler = new VclCompiler(new VclRegistry(new VclParser()), VclUnits.standard());
    private final UUID holder = UUID.randomUUID();
    private int offset;

    WorkerJournalWindowOpening(DataSource source, WorkerRuleCompilationSnapshots snapshots) {
        store = new JdbcKeyedStateStore(source);
        this.snapshots = snapshots;
    }

    @Scheduled(fixedDelayString = "${app.vcl.worker.delay:5s}")
    public void poll() {
        var page = snapshots.openingCandidates(PAGE_SIZE, offset);
        offset = page.size() < PAGE_SIZE || offset + PAGE_SIZE > 1_000_000 ? 0 : offset + PAGE_SIZE;
        for (var candidate : page) {
            try {
                snapshots.openPage(candidate, store, compiler, holder);
            } catch (RuntimeException pending) {
                LOG.warn("VCL journal opening remains pending: {}",
                        pending instanceof VclException vcl ? vcl.code() : pending.getClass().getSimpleName());
            }
        }
    }
}
