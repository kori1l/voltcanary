package com.voltcanary.ingestion.connector;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

@Component
public final class BoundedConnectorTasks implements ConnectorTaskDispatcher {

    private final ExecutorService heartbeats;
    private final ExecutorService work;

    public BoundedConnectorTasks(ConnectorRuntimeProperties properties) {
        heartbeats = pool(
            "connector-lease-",
            properties.getHeartbeatConcurrency(),
            properties.getWorkQueueCapacity()
        );
        work = pool(
            "connector-io-",
            properties.getWorkConcurrency(),
            properties.getWorkQueueCapacity()
        );
    }

    private ExecutorService pool(String name, int threads, int queued) {
        if (threads < 1 || threads > 128 || queued < 1 || queued > 4096) {
            throw new IllegalArgumentException("Invalid connector execution budget");
        }
        return new ThreadPoolExecutor(
            threads,
            threads,
            0,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(queued),
            Thread.ofPlatform().name(name, 0).factory(),
            new ThreadPoolExecutor.AbortPolicy()
        );
    }

    @Override
    public boolean heartbeat(Runnable task) {
        return submit(heartbeats, task);
    }

    @Override
    public boolean work(Runnable task) {
        return submit(work, task);
    }

    private boolean submit(ExecutorService pool, Runnable task) {
        try {
            pool.execute(task);
            return true;
        } catch (RejectedExecutionException busy) {
            return false;
        }
    }

    @PreDestroy
    public void close() {
        work.shutdownNow();
        heartbeats.shutdownNow();
    }
}
