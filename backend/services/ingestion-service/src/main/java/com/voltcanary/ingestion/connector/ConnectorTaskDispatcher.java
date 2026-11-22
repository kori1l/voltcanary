package com.voltcanary.ingestion.connector;

/** Separate bounded budgets prevent blocking adapter I/O from starving ownership heartbeats. */
public interface ConnectorTaskDispatcher {
    boolean heartbeat(Runnable task);
    boolean work(Runnable task);
}
