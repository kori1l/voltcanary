package com.voltcanary.vcl;

import com.fasterxml.jackson.databind.JsonNode;

/** A grammar-validated immutable snapshot, not an authorization or executable owner command. */
public final class VclDocument {
    public enum Kind { COLLECTION, QUERY, IMPORT, RULE, CONNECTION, MUTATION }
    private final Kind kind;
    private final JsonNode value;

    VclDocument(JsonNode value) {
        this.value = value.deepCopy();
        this.kind = Kind.valueOf(value.path("kind").textValue());
    }

    public Kind kind() { return kind; }
    public JsonNode json() { return value.deepCopy(); }
    public JsonNode spec() { return value.path("spec").deepCopy(); }
}
