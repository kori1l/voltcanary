package com.voltcanary.ingestion.connector;

public enum ConnectorRuntimeState {
    STARTING,
    RUNNING,
    DEGRADED,
    STOPPED,
    FAILED,
    LEASE_LOST
}
