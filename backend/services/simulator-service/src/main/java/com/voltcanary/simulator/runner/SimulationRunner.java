package com.voltcanary.simulator.runner;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

@Component
public class SimulationRunner {

    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(4);

    public ScheduledFuture<?> schedule(UUID runId, Runnable tick, long intervalSeconds) {
        return executor.scheduleAtFixedRate(tick, 0, Math.max(1, intervalSeconds), TimeUnit.SECONDS);
    }
}
