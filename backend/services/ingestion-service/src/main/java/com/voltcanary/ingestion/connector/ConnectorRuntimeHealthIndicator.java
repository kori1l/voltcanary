package com.voltcanary.ingestion.connector;

import java.util.List;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("connectorRuntimeHealth")
public class ConnectorRuntimeHealthIndicator implements HealthIndicator {

    private final ConnectorHealthManager healthManager;

    public ConnectorRuntimeHealthIndicator(ConnectorHealthManager healthManager) {
        this.healthManager = healthManager;
    }

    @Override
    public Health health() {
        List<ConnectorHealthSnapshot> snapshots = healthManager.snapshots();
        boolean failed = snapshots.stream().anyMatch(snapshot -> snapshot.state() == ConnectorRuntimeState.FAILED);
        boolean degraded = snapshots.stream().anyMatch(snapshot -> snapshot.state() == ConnectorRuntimeState.DEGRADED
                || snapshot.state() == ConnectorRuntimeState.LEASE_LOST);
        Health.Builder builder = failed ? Health.down() : degraded ? Health.status("DEGRADED") : Health.up();
        return builder
                .withDetail("sources", snapshots.size())
                .withDetail("states", snapshots)
                .build();
    }
}
