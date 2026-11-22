package com.voltcanary.vcl;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;
import java.util.UUID;

/** Owner-issued source schema pins. A schema description is not an execution or coverage grant. */
public record VclSourceBindingDescription(
    UUID id,
    UUID accountId,
    UUID actorId,
    UUID deviceId,
    long schemaRevision,
    long currentSchemaRevision,
    UUID snapshotId,
    JsonNode definition,
    String definitionDigest,
    String accessScopeDigest,
    String sourceStateDigest,
    boolean sourceActive,
    boolean ruleExecutionReady,
    JsonNode captureCapabilities
) {
    private static final VclParser PARSER = new VclParser();
    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
        new com.fasterxml.jackson.databind.ObjectMapper();

    public VclSourceBindingDescription {
        java.util.Objects.requireNonNull(id);
        java.util.Objects.requireNonNull(accountId);
        java.util.Objects.requireNonNull(actorId);
        java.util.Objects.requireNonNull(deviceId);
        java.util.Objects.requireNonNull(snapshotId);
        if (
            schemaRevision < 1 ||
            currentSchemaRevision < schemaRevision ||
            !definitionDigest.matches("[0-9a-f]{64}") ||
            !accessScopeDigest.matches("[0-9a-f]{64}") ||
            !sourceStateDigest.matches("[0-9a-f]{64}")
        ) throw new IllegalArgumentException("Invalid source binding snapshot");
        definition = definition.deepCopy();
        VclSourceBindingProfile.sampleHoldUs(definition);
        VclSourceBindingProfile.intervalFields(definition);
        VclSourceBindingProfile.occurrenceFields(definition);
        captureCapabilities = captureCapabilities.deepCopy();
        if (
            !id.toString().equals(definition.path("id").asText()) ||
            definition.path("schemaRevision").asLong() != schemaRevision ||
            !definitionDigest.equals(VclCanonical.sha256(VclCanonical.encode(definition)))
        ) {
            throw new IllegalArgumentException("Invalid source binding identity");
        }
        if (
            !definition.path("schemaRevision").isIntegralNumber() ||
            !definition.path("schemaRevision").canConvertToLong() ||
            !VclSourceBindingProfile.VERSION.equals(definition.path("profile").asText()) ||
            ruleExecutionReady ||
            !VclSourceBindingProfile.capabilities().equals(captureCapabilities)
        ) {
            throw new IllegalArgumentException("Unsupported source binding profile");
        }
    }

    @Override
    public JsonNode definition() {
        return definition.deepCopy();
    }

    @Override
    public JsonNode captureCapabilities() {
        return captureCapabilities.deepCopy();
    }

    @Override
    public String toString() {
        return "VclSourceBindingDescription[id=" + id + ", schemaRevision=" + schemaRevision + "]";
    }

    /** Structural checking only. Callers must obtain bytes from the authenticated owner transport. */
    public static VclSourceBindingDescription parse(byte[] bytes) {
        try {
            var json = PARSER.readJson(bytes);
            var fields = new java.util.HashSet<String>();
            json.fieldNames().forEachRemaining(fields::add);
            if (
                !fields.equals(
                    Set.of(
                        "id",
                        "accountId",
                        "actorId",
                        "deviceId",
                        "schemaRevision",
                        "currentSchemaRevision",
                        "snapshotId",
                        "definition",
                        "definitionDigest",
                        "accessScopeDigest",
                        "sourceStateDigest",
                        "sourceActive",
                        "ruleExecutionReady",
                        "captureCapabilities"
                    )
                ) ||
                !json.path("schemaRevision").isIntegralNumber() ||
                !json.path("schemaRevision").canConvertToLong() ||
                !json.path("currentSchemaRevision").isIntegralNumber() ||
                !json.path("currentSchemaRevision").canConvertToLong() ||
                !json.path("sourceActive").isBoolean() ||
                !json.path("ruleExecutionReady").isBoolean()
            ) {
                throw new VclException("OWNER_SNAPSHOT_INVALID", "");
            }
            return MAPPER.treeToValue(json, VclSourceBindingDescription.class);
        } catch (java.io.IOException | IllegalArgumentException | NullPointerException failure) {
            throw new VclException("OWNER_SNAPSHOT_INVALID", "");
        }
    }
}
