package com.voltcanary.device.configurable.port;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

/** Actual dependency-owner observations; unavailable/incomplete owners never mean no dependencies. */
public interface NativeMigrationDependencies {
    JsonNode inspect(UUID account, UUID actor, UUID dataset, long revision);
}
