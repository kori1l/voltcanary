package com.voltcanary.dashboard.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.TreeMap;

/** Stable object ordering; sets in the wire contract are sorted before hashing. */
final class QueryHash {
    private QueryHash() { }
    static String of(ObjectMapper mapper, Object value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical(mapper, mapper.valueToTree(value)).toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }
    private static JsonNode canonical(ObjectMapper mapper, JsonNode value) {
        if (value.isObject()) {
            TreeMap<String, JsonNode> fields = new TreeMap<>();
            value.fields().forEachRemaining(entry -> fields.put(entry.getKey(), canonical(mapper, entry.getValue())));
            var result = mapper.createObjectNode();
            fields.forEach(result::set);
            return result;
        }
        if (value.isArray()) {
            var items = mapper.createArrayNode();
            value.forEach(item -> items.add(canonical(mapper, item)));
            // Array order is material (widgets/series); scope sets are normalized by their resolver.
            return items;
        }
        return value;
    }
}
