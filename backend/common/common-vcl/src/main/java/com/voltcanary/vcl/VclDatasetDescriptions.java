package com.voltcanary.vcl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Set;

/** Shared strict decoding for the implemented native owner profile. It does not establish access. */
public final class VclDatasetDescriptions {
    private static final VclParser PARSER = new VclParser();
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private VclDatasetDescriptions() { }

    public static VclDatasetDescription parse(byte[] bytes) {
        try {
            var json = PARSER.readJson(bytes);
            var keys = new java.util.HashSet<String>();
            json.fieldNames().forEachRemaining(keys::add);
            if (!keys.equals(Set.of("id", "schemaRevision", "definition", "schemaDigest", "accessScopeDigest",
                    "generation", "dataVersion", "readyIndexedFields", "storageProfile", "liveQueryReady", "historyReady", "queryCapabilities"))
                    || !json.path("schemaRevision").isIntegralNumber() || !json.path("schemaRevision").canConvertToLong()
                    || !json.path("dataVersion").isIntegralNumber() || !json.path("dataVersion").canConvertToLong()
                    || !json.path("liveQueryReady").isBoolean() || !json.path("historyReady").isBoolean()) throw new IllegalArgumentException();
            var value = MAPPER.treeToValue(json, VclDatasetDescription.class);
            var definition = (ObjectNode) value.definition();
            if (!value.id().toString().equals(definition.path("id").asText())
                    || !definition.path("revision").isIntegralNumber() || definition.path("revision").asLong() != value.schemaRevision()
                    || !value.generation().toString().equals(definition.path("generation").asText())
                    || !definition.path("dataVersion").isIntegralNumber() || definition.path("dataVersion").asLong() != value.dataVersion()
                    || !value.storageProfile().equals("native-dense-1") || !definition.at("/storage/mode").asText().equals("NATIVE")) throw new IllegalArgumentException();
            var systemId = java.util.UUID.fromString(definition.at("/systemFields/recordIdFieldId").asText());
            var logical = new java.util.HashSet<java.util.UUID>();
            definition.path("fields").forEach(field -> logical.add(java.util.UUID.fromString(field.path("id").asText())));
            var declared = new java.util.HashSet<java.util.UUID>();
            definition.at("/storage/indexedFieldIds").forEach(id -> declared.add(java.util.UUID.fromString(id.asText())));
            if (logical.contains(systemId) || !logical.containsAll(declared) || !declared.containsAll(value.readyIndexedFields())
                    || json.path("readyIndexedFields").size() != value.readyIndexedFields().size()) throw new IllegalArgumentException();
            definition.remove(List.of("generation", "dataVersion", "systemFields"));
            var collection = MAPPER.createObjectNode().put("language", "VCL/1").put("kind", "COLLECTION");
            collection.set("spec", definition);
            if (!value.schemaDigest().equals(VclCanonical.documentDigest(PARSER.parse(collection.toString())))) throw new IllegalArgumentException();
            return value;
        } catch (java.io.IOException | IllegalArgumentException | NullPointerException | ClassCastException failure) {
            throw new VclException("OWNER_SNAPSHOT_INVALID", "");
        }
    }
}
