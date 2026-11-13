package com.voltcanary.analytics.vcl.adapter;

import com.voltcanary.vcl.VclException;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** Bounded local close of unfinished temporal work after a durable rule pause. */
final class WorkerPausedWindows {
    private static final Logger LOG = LoggerFactory.getLogger(WorkerPausedWindows.class);
    private static final int PAGE_SIZE = 16;
    private final JdbcKeyedStateStore store;
    private final JdbcRuleDeployments deployments;
    private int offset;
    private int sustainedOffset;

    WorkerPausedWindows(DataSource source) {
        store = new JdbcKeyedStateStore(source);
        deployments = new JdbcRuleDeployments(source, new JdbcCompilationBundleStore(source));
    }

    @Scheduled(fixedDelayString = "${app.vcl.worker.delay:5s}")
    public void poll() {
        var page = store.pausedWindows(PAGE_SIZE, offset);
        offset = page.size() < PAGE_SIZE || offset + PAGE_SIZE > 1_000_000 ? 0 : offset + PAGE_SIZE;
        for (var pending : page) {
            try {
                var key = pending.key();
                var deployment = deployments.find(key.accountId(), key.deploymentEpoch())
                        .orElseThrow(() -> new VclException("DEPLOYMENT_UNAVAILABLE", ""));
                store.closePausedWindow(pending, deployment);
            } catch (RuntimeException unavailable) {
                LOG.warn("VCL paused window remains pending: {}",
                        unavailable instanceof VclException vcl ? vcl.code() : unavailable.getClass().getSimpleName());
            }
        }
        var episodes = store.pausedSustained(PAGE_SIZE, sustainedOffset);
        sustainedOffset = episodes.size() < PAGE_SIZE || sustainedOffset + PAGE_SIZE > 1_000_000
                ? 0 : sustainedOffset + PAGE_SIZE;
        for (var pending : episodes) {
            try {
                var key = pending.key();
                var deployment = deployments.find(key.accountId(), key.deploymentEpoch())
                        .orElseThrow(() -> new VclException("DEPLOYMENT_UNAVAILABLE", ""));
                store.closePausedSustained(pending, deployment);
            } catch (RuntimeException unavailable) {
                LOG.warn("VCL paused sustained episode remains pending: {}",
                        unavailable instanceof VclException vcl ? vcl.code() : unavailable.getClass().getSimpleName());
            }
        }
    }
}
