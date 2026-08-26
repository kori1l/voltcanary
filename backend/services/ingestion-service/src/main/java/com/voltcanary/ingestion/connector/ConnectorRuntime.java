package com.voltcanary.ingestion.connector;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ConnectorRuntime {

    private final ConnectorRuntimeProperties properties;
    private final SourceAssignmentManager assignmentManager;
    private final ConnectorSupervisor supervisor;

    public ConnectorRuntime(
            ConnectorRuntimeProperties properties,
            SourceAssignmentManager assignmentManager,
            ConnectorSupervisor supervisor) {
        this.properties = properties;
        this.assignmentManager = assignmentManager;
        this.supervisor = supervisor;
    }

    @Scheduled(fixedDelayString = "#{@connectorRuntimeProperties.tickInterval.toMillis()}")
    public void tick() {
        if (!properties.isEnabled()) {
            return;
        }
        supervisor.reconcile(assignmentManager.assignedSources());
        supervisor.tick();
    }
}
