package com.voltcanary.device.configurable.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.UUID;

/** Immutable head snapshot. A current head is refreshed and fenced at every write. */
public record NativeDataset(UUID accountId, UUID generation, long dataVersion, UUID recordIdFieldId, NativeSchema schema) {
    public JsonNode metadata() {
        var result = (ObjectNode) schema.definition();
        result.put("generation", generation.toString()).put("dataVersion", dataVersion);
        result.putObject("systemFields").put("recordIdFieldId", recordIdFieldId.toString());
        return result;
    }
}
