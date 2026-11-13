package com.voltcanary.device.configurable.domain;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public record NativeRow(UUID recordId, String recordVersion, Map<UUID, JsonNode> values) {
    public NativeRow { values = copy(values); }
    @Override public Map<UUID, JsonNode> values() { return copy(values); }
    private static Map<UUID, JsonNode> copy(Map<UUID, JsonNode> values) {
        var result = new LinkedHashMap<UUID, JsonNode>();
        values.forEach((id, value) -> result.put(id, value.deepCopy()));
        return java.util.Collections.unmodifiableMap(result);
    }
}
