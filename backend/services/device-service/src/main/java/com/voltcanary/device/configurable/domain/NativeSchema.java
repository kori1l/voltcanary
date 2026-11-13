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
import java.util.List;
import java.util.UUID;

/** Closed, statically checked native collection schema. Labels never become SQL identifiers. */
public final class NativeSchema {
    private static final VclParser PARSER = new VclParser();
    private static final VclCompiler COMPILER = new VclCompiler(new VclRegistry(PARSER), VclUnits.standard());
    private final VclDocument document;
    private final String canonical;
    private final String digest;

    private NativeSchema(VclDocument document, String canonical) {
        this.document = document;
        this.canonical = canonical;
        digest = VclCanonical.sha256(canonical);
    }

    public static NativeSchema compile(String json, VclMetadata metadata) {
        var document = PARSER.parse(json);
        if (document.kind() != VclDocument.Kind.COLLECTION || !document.spec().at("/storage/mode").asText().equals("NATIVE")) {
            throw new VclException("NATIVE_COLLECTION_REQUIRED", "");
        }
        var result = COMPILER.compile(document, metadata);
        if (document.spec().path("revision").asLong() != 1) throw new VclException("SCHEMA_MIGRATION_REQUIRED", "/spec/revision");
        return new NativeSchema(PARSER.parse(result.canonicalDocument()), result.canonicalDocument());
    }

    public UUID id() { return UUID.fromString(document.spec().path("id").asText()); }
    public long revision() { return document.spec().path("revision").asLong(); }
    public String canonical() { return canonical; }
    public String digest() { return digest; }
    public JsonNode definition() { return document.spec(); }
    public boolean history() { return document.spec().at("/storage/history").asText().equals("REVISIONS"); }
    public List<JsonNode> fields() {
        var result = new ArrayList<JsonNode>();
        document.spec().path("fields").forEach(field -> result.add(field.deepCopy()));
        return List.copyOf(result);
    }
    public List<UUID> keys() { return ids(document.spec().path("keyFieldIds")); }
    public List<UUID> indexes() { return ids(document.spec().at("/storage/indexedFieldIds")); }

    public JsonNode field(UUID id) {
        return fields().stream().filter(field -> field.path("id").asText().equals(id.toString())).findFirst()
                .orElseThrow(() -> new VclException("FIELD_NOT_IN_SCOPE", ""));
    }
    public VclType type(UUID id) { return VclType.from(field(id)); }

    private static List<UUID> ids(JsonNode values) {
        var result = new ArrayList<UUID>();
        values.forEach(value -> result.add(UUID.fromString(value.asText())));
        return List.copyOf(result);
    }

    @Override public String toString() { return "NativeSchema[id=" + id() + ", digest=" + digest + "]"; }
}
