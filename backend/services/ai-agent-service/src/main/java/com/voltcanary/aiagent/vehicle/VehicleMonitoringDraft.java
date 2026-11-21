package com.voltcanary.aiagent.vehicle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Bounded edits preserve every omitted zone and threshold. No model-supplied replacement document. */
public final class VehicleMonitoringDraft {
    private VehicleMonitoringDraft() { }
    public static ObjectNode apply(JsonNode current, JsonNode command, String identity) {
        var result = (ObjectNode) current.deepCopy();
        if (!command.path("speedLimitKmh").isNull()) result.put("speedLimitKmh", command.path("speedLimitKmh").doubleValue());
        if (!command.path("idleMinutes").isNull()) result.put("idleMinutes", command.path("idleMinutes").intValue());
        var zones = result.withArray("zones");
        var touched = new HashSet<String>();
        int index = 0;
        for (var edit : command.path("zoneEdits")) {
            String op = edit.path("op").asText();
            if (op.equals("ADD")) {
                if (!edit.path("zoneId").isNull()) throw new IllegalArgumentException("New zone identities are server assigned");
                for (String key : Set.of("name", "latitude", "longitude", "radiusMetres")) if (edit.path(key).isNull()) throw new IllegalArgumentException("New zone needs explicit name, centre and radius");
                var zone = zones.addObject().put("id", UUID.nameUUIDFromBytes((identity + "/" + index).getBytes(StandardCharsets.UTF_8)).toString());
                for (String key : Set.of("name", "latitude", "longitude", "radiusMetres")) field(zone, key, edit.path(key));
            } else {
                String id = edit.path("zoneId").asText();
                if (!touched.add(id)) throw new IllegalArgumentException("Edit each zone once");
                int selected = -1;
                for (int i = 0; i < zones.size(); i++) if (zones.get(i).path("id").asText().equals(id)) selected = i;
                if (selected < 0) throw new IllegalArgumentException("Choose an existing zone identity");
                if (op.equals("REMOVE")) {
                    for (String key : Set.of("name", "latitude", "longitude", "radiusMetres")) if (!edit.path(key).isNull()) throw new IllegalArgumentException("Removal must not contain geometry edits");
                    zones.remove(selected);
                } else if (op.equals("UPDATE")) {
                    for (String key : Set.of("name", "latitude", "longitude", "radiusMetres")) if (!edit.path(key).isNull()) field((ObjectNode) zones.get(selected), key, edit.path(key));
                } else throw new IllegalArgumentException("Unknown zone operation");
            }
            index++;
        }
        if (zones.size() > 50) throw new IllegalArgumentException("At most 50 vehicle zones");
        return result;
    }
    private static void field(ObjectNode zone, String key, JsonNode value) {
        if (key.equals("name")) zone.set(key, value);
        else zone.put(key, value.doubleValue());
    }
}
