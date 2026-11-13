package com.voltcanary.vcl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

final class VclValues {
    private static final java.util.Set<String> UUID_FIELDS = java.util.Set.of("datasetId", "sourceDatasetId", "ruleId",
            "connectionId", "bindingId", "executionGrantId", "evidencePolicyId", "fieldId", "eventFieldId", "recordFieldId",
            "subjectFieldId", "occurrenceFieldId", "zoneFieldId", "positionFieldId", "accuracyFieldId", "startFieldId",
            "endFieldId", "measureFieldId", "valueFieldId", "targetFieldId", "keyFieldId", "mutationId", "reviewId",
            "artifactId", "endpointRef", "secretRef", "objectId", "generation", "adapterContractId", "recordIdFieldId");
    private static final java.util.Set<String> UUID_ARRAYS = java.util.Set.of("select", "keyFields", "keyFieldIds", "indexedFieldIds", "objectSelection");
    private VclValues() { }

    static JsonNode normalized(JsonNode input) {
        JsonNode result = input.deepCopy();
        boolean externalMutation = result.at("/targetPrecondition/mode").asText().equals("EXTERNAL")
                || result.at("/spec/targetPrecondition/mode").asText().equals("EXTERNAL");
        normalize(result, "", externalMutation);
        return result;
    }

    private static void normalize(JsonNode node, String path, boolean externalMutation) {
        boolean sourceKey = externalMutation && (path.startsWith("/key/") || path.startsWith("/spec/key/"));
        if (node.isObject() && node.has("type") && node.has("value")) literal((ObjectNode) node, path, sourceKey);
        if (node.isObject()) {
            var object = (ObjectNode) node;
            for (String key : UUID_FIELDS) if (node.path(key).isTextual()) canonicalUuid(object, key);
            if (node.has("id") && (node.has("type") || node.has("fields"))) canonicalUuid(object, "id");
            for (String key : UUID_ARRAYS) {
                if (!node.path(key).isArray()) continue;
                var array = (com.fasterxml.jackson.databind.node.ArrayNode) node.path(key);
                for (int i = 0; i < array.size(); i++) {
                    if (array.get(i).isTextual() && uuid(array.get(i).asText())) {
                        array.set(i, com.fasterxml.jackson.databind.node.TextNode.valueOf(array.get(i).asText().toLowerCase(java.util.Locale.ROOT)));
                    }
                }
            }
        }
        if (node.isObject()) node.fields().forEachRemaining(e -> normalize(e.getValue(), path + "/" + e.getKey(), externalMutation));
        else if (node.isArray()) for (int i = 0; i < node.size(); i++) normalize(node.get(i), path + "/" + i, externalMutation);
    }

    private static boolean uuid(String value) { return value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"); }

    private static void canonicalUuid(ObjectNode node, String key) {
        if (node.path(key).isTextual() && uuid(node.path(key).asText())) node.put(key, node.path(key).asText().toLowerCase(java.util.Locale.ROOT));
    }

    private static void literal(ObjectNode node, String path, boolean sourceKey) {
        try {
            switch (VclType.Kind.valueOf(node.path("type").asText())) {
                case INT64, DURATION -> Long.parseLong(node.path("value").asText());
                case DECIMAL -> {
                    var value = new BigDecimal(node.path("value").asText());
                    VclException.require(value.precision() <= 38 && Math.max(value.scale(), 0) <= 12, "PRECISION_LOSS", path);
                    node.put("value", value.signum() == 0 ? "0" : value.stripTrailingZeros().toPlainString());
                }
                case TEXT -> {
                    if (!sourceKey) node.put("value", Normalizer.normalize(node.path("value").asText(), Normalizer.Form.NFC));
                }
                case DATE -> {
                    var value = LocalDate.parse(node.path("value").asText());
                    VclException.require(value.getYear() >= 1 && value.getYear() <= 9999, "DATE_RANGE", path);
                }
                case INSTANT -> node.put("value", instant(node.path("value").asText(), path).toString());
                case REF -> node.put("value", UUID.fromString(node.path("value").asText()).toString());
                case INTERVAL -> {
                    var value = (ObjectNode) node.path("value");
                    Instant start = instant(value.path("start").asText(), path);
                    value.put("start", start.toString());
                    if (!value.path("end").isNull()) {
                        Instant end = instant(value.path("end").asText(), path);
                        VclException.require(!end.isBefore(start), "INTERVAL_REVERSED", path);
                        value.put("end", end.toString());
                    }
                }
                default -> { /* Shape and point ranges are checked by the closed grammar. */ }
            }
        } catch (NumberFormatException error) {
            throw new VclException("NUMBER_RANGE", path);
        } catch (java.time.DateTimeException error) {
            throw new VclException("DATE_RANGE", path);
        }
    }

    static Instant instant(String text, String path) {
        try {
            var value = OffsetDateTime.parse(text);
            VclException.require(value.getYear() >= 1 && value.getYear() <= 9999 && value.getNano() % 1000 == 0,
                    "INSTANT_RANGE", path);
            return value.toInstant();
        } catch (java.time.DateTimeException error) {
            throw new VclException("INSTANT_RANGE", path);
        }
    }
}
