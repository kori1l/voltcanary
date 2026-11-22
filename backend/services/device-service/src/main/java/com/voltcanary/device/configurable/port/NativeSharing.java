package com.voltcanary.device.configurable.port;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

public interface NativeSharing {
    JsonNode prepare(UUID account, UUID actor, UUID dataset, String key, JsonNode definition);
    JsonNode inspect(UUID account, UUID actor, UUID dataset, UUID review);
    JsonNode apply(
        UUID account,
        UUID actor,
        UUID dataset,
        UUID review,
        long revision,
        String digest,
        String key
    );
    JsonNode list(UUID account, UUID actor, UUID dataset);
    JsonNode revoke(
        UUID account,
        UUID actor,
        UUID dataset,
        UUID grant,
        long expectedRevision,
        String key
    );
}
