package com.voltcanary.vcl;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Closed implemented policy profile. Structural validation never replaces authenticated owner reads. */
public record VclEvidencePolicyDescription(
    UUID id,
    UUID accountId,
    UUID actorId,
    UUID ruleId,
    UUID grantId,
    String grantEpoch,
    String grantDefinitionDigest,
    String status,
    boolean usable,
    Instant expiresAt,
    String definitionDigest,
    JsonNode definition
) {
    public static final String PROFILE = "private-reference-evidence-1";
    private static final VclParser PARSER = new VclParser();

    public VclEvidencePolicyDescription {
        java.util.Objects.requireNonNull(id);
        java.util.Objects.requireNonNull(accountId);
        java.util.Objects.requireNonNull(actorId);
        java.util.Objects.requireNonNull(ruleId);
        java.util.Objects.requireNonNull(grantId);
        if (
            !grantEpoch.matches("[1-9][0-9]{0,18}") ||
            Long.parseLong(grantEpoch) < 1 ||
            !grantDefinitionDigest.matches("[0-9a-f]{64}") ||
            !definitionDigest.matches("[0-9a-f]{64}") ||
            !(usable ? "ACTIVE" : "INACTIVE").equals(status)
        ) throw new IllegalArgumentException();
        int year = expiresAt.atOffset(java.time.ZoneOffset.UTC).getYear();
        if (
            year < 1 || year > 9999 || expiresAt.getNano() % 1000 != 0
        ) throw new IllegalArgumentException();
        definition = definition.deepCopy();
        fields(definition, Set.of("mode", "retentionDays", "maxObservationReferences"));
        if (
            !"PRIVATE_REFERENCES".equals(definition.path("mode").textValue()) ||
            !bounded(definition.path("retentionDays"), 30) ||
            !bounded(definition.path("maxObservationReferences"), 100) ||
            !definitionDigest.equals(VclCanonical.sha256(VclCanonical.encode(definition)))
        ) throw new IllegalArgumentException();
    }

    @Override
    public JsonNode definition() {
        return definition.deepCopy();
    }

    @Override
    public String toString() {
        return "VclEvidencePolicyDescription[id=" + id + "]";
    }

    public static VclEvidencePolicyDescription parse(byte[] bytes) {
        try {
            var value = PARSER.readJson(bytes);
            fields(
                value,
                Set.of(
                    "id",
                    "accountId",
                    "actorId",
                    "ruleId",
                    "grantId",
                    "grantEpoch",
                    "grantDefinitionDigest",
                    "profile",
                    "revision",
                    "status",
                    "usable",
                    "expiresAt",
                    "definitionDigest",
                    "definition"
                )
            );
            if (
                !PROFILE.equals(value.path("profile").textValue()) ||
                !value.path("revision").isIntegralNumber() ||
                value.path("revision").asLong() != 1 ||
                !value.path("grantEpoch").isTextual() ||
                !value.path("usable").isBoolean()
            ) throw new IllegalArgumentException();
            var expiry = Instant.parse(value.path("expiresAt").textValue());
            if (
                !expiry.toString().equals(value.path("expiresAt").textValue())
            ) throw new IllegalArgumentException();
            return new VclEvidencePolicyDescription(
                uuid(value.path("id")),
                uuid(value.path("accountId")),
                uuid(value.path("actorId")),
                uuid(value.path("ruleId")),
                uuid(value.path("grantId")),
                value.path("grantEpoch").textValue(),
                value.path("grantDefinitionDigest").textValue(),
                value.path("status").textValue(),
                value.path("usable").booleanValue(),
                expiry,
                value.path("definitionDigest").textValue(),
                value.path("definition")
            );
        } catch (
            IllegalArgumentException
            | NullPointerException
            | java.time.DateTimeException failure
        ) {
            throw new VclException("OWNER_SNAPSHOT_INVALID", "");
        }
    }

    private static UUID uuid(JsonNode value) {
        if (!value.isTextual()) throw new IllegalArgumentException();
        var id = UUID.fromString(value.textValue());
        if (!id.toString().equals(value.textValue())) throw new IllegalArgumentException();
        return id;
    }

    private static boolean bounded(JsonNode value, int maximum) {
        return (
            value.isIntegralNumber() &&
            value.canConvertToInt() &&
            value.intValue() >= 1 &&
            value.intValue() <= maximum
        );
    }

    private static void fields(JsonNode node, Set<String> expected) {
        var actual = new HashSet<String>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!node.isObject() || !actual.equals(expected)) throw new IllegalArgumentException();
    }
}
