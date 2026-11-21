package com.voltcanary.device.configurable.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclCompiler;
import com.voltcanary.vcl.VclDocument;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclMetadata;
import com.voltcanary.vcl.VclParser;
import com.voltcanary.vcl.VclRegistry;
import com.voltcanary.vcl.VclType;
import com.voltcanary.vcl.VclUnits;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Closed, statically checked native collection schema. Labels never become SQL identifiers. */
public final class NativeSchema {
    private static final VclParser PARSER = new VclParser();
    private static final VclCompiler COMPILER = new VclCompiler(new VclRegistry(PARSER), VclUnits.standard());
    private final JsonNode definition;
    private final String canonical;
    private final String digest;
    private final UUID id;
    private final long revision;
    private final boolean history;
    private final List<UUID> keys;
    private final List<UUID> indexes;
    private final Map<UUID, JsonNode> fields;
    private final Map<UUID, VclType> types;

    private NativeSchema(VclDocument document, String canonical) {
        definition = document.spec();
        this.canonical = canonical;
        digest = VclCanonical.sha256(canonical);
        id = UUID.fromString(definition.path("id").asText());
        revision = definition.path("revision").asLong();
        history = definition.at("/storage/history").asText().equals("REVISIONS");
        keys = ids(definition.path("keyFieldIds"));
        indexes = ids(definition.at("/storage/indexedFieldIds"));
        var fieldsById = new LinkedHashMap<UUID, JsonNode>();
        var typesById = new LinkedHashMap<UUID, VclType>();
        for (var field : definition.path("fields")) {
            var fieldId = UUID.fromString(field.path("id").asText());
            fieldsById.put(fieldId, field);
            typesById.put(fieldId, VclType.from(field));
        }
        fields = java.util.Collections.unmodifiableMap(fieldsById);
        types = Map.copyOf(typesById);
    }

    public static NativeSchema compile(String json, VclMetadata metadata) {
        var schema = compileRevision(json, metadata);
        if (schema.revision() != 1) throw new VclException("SCHEMA_MIGRATION_REQUIRED", "/spec/revision");
        return schema;
    }

    public static NativeSchema compileRevision(String json, VclMetadata metadata) {
        var document = PARSER.parse(json);
        if (document.kind() != VclDocument.Kind.COLLECTION || !document.spec().at("/storage/mode").asText().equals("NATIVE")) {
            throw new VclException("NATIVE_COLLECTION_REQUIRED", "");
        }
        var result = COMPILER.compile(document, metadata);
        return new NativeSchema(PARSER.parse(result.canonicalDocument()), result.canonicalDocument());
    }

    public UUID id() { return id; }
    public long revision() { return revision; }
    public String canonical() { return canonical; }
    public String digest() { return digest; }
    public JsonNode definition() { return definition.deepCopy(); }
    public boolean history() { return history; }
    public List<JsonNode> fields() {
        var result = new ArrayList<JsonNode>();
        fields.values().forEach(field -> result.add(field.deepCopy()));
        return List.copyOf(result);
    }
    public List<UUID> keys() { return keys; }
    public List<UUID> indexes() { return indexes; }

    public JsonNode field(UUID id) {
        var field = fields.get(id);
        if (field == null) throw new VclException("FIELD_NOT_IN_SCOPE", "");
        return field.deepCopy();
    }

    public VclType type(UUID id) {
        var type = types.get(id);
        if (type == null) throw new VclException("FIELD_NOT_IN_SCOPE", "");
        return type;
    }

    private static List<UUID> ids(JsonNode values) {
        var result = new ArrayList<UUID>();
        values.forEach(value -> result.add(UUID.fromString(value.asText())));
        return List.copyOf(result);
    }

    @Override public String toString() { return "NativeSchema[id=" + id() + ", digest=" + digest + "]"; }
}
