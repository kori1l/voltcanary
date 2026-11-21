package com.voltcanary.ingestion.connector;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ConnectorRuntime {

    private final ConnectorRuntimeProperties properties;
    private final SourceAssignmentManager assignmentManager;
    private final ConnectorSupervisor supervisor;

    @Scheduled(fixedDelayString = "#{@connectorRuntimeProperties.tickInterval.toMillis()}")
    public void tick() {
        if (!properties.isEnabled()) {
            return;
        }
        supervisor.reconcile(assignmentManager.assignedSources());
        supervisor.tick();
    }
}
