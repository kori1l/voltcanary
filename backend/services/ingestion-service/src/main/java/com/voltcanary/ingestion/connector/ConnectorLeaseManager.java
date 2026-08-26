package com.voltcanary.ingestion.connector;

import java.util.UUID;

public interface ConnectorLeaseManager {

    boolean acquire(UUID dataSourceId);

    boolean renew(UUID dataSourceId);

    void release(UUID dataSourceId);

    boolean isOwned(UUID dataSourceId);
}
