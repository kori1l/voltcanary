package com.voltcanary.ingestion.connector;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class ConnectorHealthManager {

    private final Map<String, ConnectorHealthSnapshot> snapshots = new ConcurrentHashMap<>();

    public void starting(String sourceId) {
        update(sourceId, ConnectorRuntimeState.STARTING, 0, null, null, null, "starting");
    }

    public void running(String sourceId, Instant now) {
        update(sourceId, ConnectorRuntimeState.RUNNING, 0, now, null, null, "running");
    }

    public void degraded(String sourceId, int failures, Instant lastFailureAt, Instant nextAttemptAt, String message) {
        update(sourceId, ConnectorRuntimeState.DEGRADED, failures, null, lastFailureAt, nextAttemptAt, message);
    }

    public void failed(String sourceId, int failures, Instant lastFailureAt, Instant nextAttemptAt, String message) {
        update(sourceId, ConnectorRuntimeState.FAILED, failures, null, lastFailureAt, nextAttemptAt, message);
    }

    public void leaseLost(String sourceId, Instant now) {
        update(sourceId, ConnectorRuntimeState.LEASE_LOST, 0, null, now, null, "lease lost");
    }

    public void stopped(String sourceId) {
        update(sourceId, ConnectorRuntimeState.STOPPED, 0, null, null, null, "stopped");
    }

    public ConnectorHealthSnapshot snapshot(String sourceId) {
        return snapshots.get(sourceId);
    }

    public List<ConnectorHealthSnapshot> snapshots() {
        return List.copyOf(snapshots.values());
    }

    private void update(
            String sourceId,
            ConnectorRuntimeState state,
            int failures,
            Instant lastSuccessAt,
            Instant lastFailureAt,
            Instant nextAttemptAt,
            String message) {
        snapshots.compute(sourceId, (ignored, previous) -> new ConnectorHealthSnapshot(
                sourceId,
                state,
                failures,
                lastSuccessAt == null && previous != null ? previous.lastSuccessAt() : lastSuccessAt,
                lastFailureAt == null && previous != null ? previous.lastFailureAt() : lastFailureAt,
                nextAttemptAt,
                message));
    }
}
