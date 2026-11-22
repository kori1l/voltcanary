package com.voltcanary.device.configurable.port;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;

public interface NativeSchemaMigrations {
    JsonNode reserve(UUID account, UUID actor, UUID dataset, String key, List<String> fieldKeys);
    JsonNode prepare(UUID account, UUID actor, UUID dataset, UUID review, JsonNode request);
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
    JsonNode archive(UUID account, UUID actor, UUID dataset, JsonNode request);
}
