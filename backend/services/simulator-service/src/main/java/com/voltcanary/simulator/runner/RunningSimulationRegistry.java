package com.voltcanary.simulator.runner;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import org.springframework.stereotype.Component;

@Component
public class RunningSimulationRegistry {
    private final Map<UUID, ScheduledFuture<?>> byDeviceId = new ConcurrentHashMap<>();

    public boolean putIfAbsent(UUID deviceId, ScheduledFuture<?> future) {
        return byDeviceId.putIfAbsent(deviceId, future) == null;
    }

    public Optional<ScheduledFuture<?>> remove(UUID deviceId) {
        return Optional.ofNullable(byDeviceId.remove(deviceId));
    }

    public boolean isRunning(UUID deviceId) {
        return byDeviceId.containsKey(deviceId);
    }

    public int activeCount() {
        return byDeviceId.size();
    }
}
