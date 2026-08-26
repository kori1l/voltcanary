package com.voltcanary.ingestion.connector;

import java.util.Optional;
import java.util.UUID;

public interface ConnectorLeaseManager {

    Optional<ConnectorLease> acquire(UUID dataSourceId);

    Optional<ConnectorLease> renew(ConnectorLease lease);

    void release(ConnectorLease lease);

    boolean isCurrent(ConnectorLease lease);

    Optional<ConnectorLease> current(UUID dataSourceId);
}
