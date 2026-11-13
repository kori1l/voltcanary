package com.voltcanary.vcl;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Structural owner response only. Bytes must come from the authenticated alert owner. */
public record VclAutomationGrantDescription(UUID id, UUID accountId, UUID actorId, UUID ruleId,
        String epoch, String status, Instant expiresAt, String definitionDigest, JsonNode definition) {
    public static final String PROFILE = "source-native-fields-grant-1";
    private static final VclParser PARSER = new VclParser();

    public VclAutomationGrantDescription {
        java.util.Objects.requireNonNull(id);
        java.util.Objects.requireNonNull(accountId);
        java.util.Objects.requireNonNull(actorId);
        java.util.Objects.requireNonNull(ruleId);
        if (!epoch.matches("[1-9][0-9]{0,18}") || Long.parseLong(epoch) < 1
                || !Set.of("ACTIVE", "EXPIRED", "REVOKED").contains(status)) throw new IllegalArgumentException();
        instant(expiresAt.toString());
        definition = definition.deepCopy();
        fields(definition, Set.of("sourceBindings", "datasets", "expiresAt"));
        resources(definition.path("sourceBindings"), true);
        resources(definition.path("datasets"), false);
        if (!expiresAt.equals(instant(definition.path("expiresAt").textValue()))
                || !definitionDigest.matches("[0-9a-f]{64}")
                || !definitionDigest.equals(VclCanonical.sha256(VclCanonical.encode(definition)))) throw new IllegalArgumentException();
    }

    @Override public JsonNode definition() { return definition.deepCopy(); }
    @Override public String toString() { return "VclAutomationGrantDescription[id=" + id + ", epoch=" + epoch + "]"; }

    public static VclAutomationGrantDescription parse(byte[] bytes) {
        try {
            var value = PARSER.readJson(bytes);
            fields(value, Set.of("id", "accountId", "actorId", "ruleId", "profile", "epoch", "status", "expiresAt",
                    "definitionDigest", "definition", "effects", "deliveryChannels"));
            if (!PROFILE.equals(value.path("profile").textValue()) || !value.path("epoch").isTextual()
                    || !value.path("effects").isArray() || value.path("effects").size() != 1
                    || !"ALERT".equals(value.path("effects").path(0).textValue())
                    || !value.path("deliveryChannels").isArray() || !value.path("deliveryChannels").isEmpty()) throw new IllegalArgumentException();
            return new VclAutomationGrantDescription(uuid(value.path("id")), uuid(value.path("accountId")),
                    uuid(value.path("actorId")), uuid(value.path("ruleId")), value.path("epoch").textValue(),
                    value.path("status").textValue(), instant(value.path("expiresAt").textValue()),
                    value.path("definitionDigest").textValue(), value.path("definition"));
        } catch (IllegalArgumentException | NullPointerException | java.time.DateTimeException failure) { throw new VclException("OWNER_SNAPSHOT_INVALID", ""); }
    }

    /** Exact resource/revision/field membership; does not assert current authority or readiness. */
    public boolean includes(VclMetadata.Resource kind, UUID resource, long revision, UUID field) {
        String list = switch (kind) { case BINDING -> "sourceBindings"; case DATASET -> "datasets"; default -> null; };
        if (list == null || resource == null || field == null) return false;
        for (var entry : definition.path(list)) {
            if (entry.path("id").asText().equals(resource.toString()) && entry.path("schemaRevision").asLong() == revision) {
                for (var id : entry.path("fieldIds")) if (id.asText().equals(field.toString())) return true;
            }
        }
        return false;
    }

    private static void resources(JsonNode values, boolean required) {
        if (!values.isArray() || values.size() > 16 || required && values.isEmpty()) throw new IllegalArgumentException();
        var ids = new HashSet<UUID>();
        for (var entry : values) {
            fields(entry, Set.of("id", "schemaRevision", "fieldIds"));
            if (!ids.add(uuid(entry.path("id"))) || !entry.path("schemaRevision").isIntegralNumber()
                    || !entry.path("schemaRevision").canConvertToLong() || entry.path("schemaRevision").asLong() < 1
                    || !entry.path("fieldIds").isArray() || entry.path("fieldIds").isEmpty()
                    || entry.path("fieldIds").size() > 64) throw new IllegalArgumentException();
            var fieldIds = new HashSet<UUID>();
            for (var field : entry.path("fieldIds")) if (!fieldIds.add(uuid(field))) throw new IllegalArgumentException();
        }
    }

    private static UUID uuid(JsonNode node) {
        if (!node.isTextual()) throw new IllegalArgumentException();
        var id = UUID.fromString(node.textValue());
        if (!id.toString().equals(node.textValue())) throw new IllegalArgumentException();
        return id;
    }

    private static Instant instant(String text) {
        var instant = Instant.parse(text);
        int year = instant.atOffset(java.time.ZoneOffset.UTC).getYear();
        if (year < 1 || year > 9999 || instant.getNano() % 1000 != 0 || !instant.toString().equals(text)) throw new IllegalArgumentException();
        return instant;
    }

    private static void fields(JsonNode node, Set<String> expected) {
        var actual = new HashSet<String>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!node.isObject() || !expected.equals(actual)) throw new IllegalArgumentException();
    }
}
