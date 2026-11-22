package com.voltcanary.analytics.vcl.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.voltcanary.analytics.config.AnalyticsDownstreamProperties;
import com.voltcanary.common.web.ConfigurableOwnerReads;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclCompilation;
import com.voltcanary.vcl.VclDocument;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclParser;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Signed event-time reads against a prepared native snapshot. */
@Component
public final class NativeAsOfOwner {

    interface Transport {
        byte[] manifest(UUID dataset, byte[] body, long deadline);
        byte[] rows(UUID dataset, byte[] body, long deadline, boolean split);
    }

    private static final Set<String> UNKNOWN_REASONS = Set.of(
        "ASOF_MISSING",
        "ASOF_AMBIGUOUS",
        "ASOF_INTERVAL_UNKNOWN"
    );
    private final ConfigurableOwnerReads reads;
    private final VclParser parser = new VclParser();

    public NativeAsOfOwner(RestClient.Builder builder, AnalyticsDownstreamProperties properties) {
        var settings = properties.getDeviceService();
        reads = new ConfigurableOwnerReads(builder, settings.getBaseUrl(), settings.getTimeout());
    }

    public JsonNode inspect(
        String authorization,
        VclDocument query,
        UUID intervalField,
        List<UUID> keyFields,
        long deadline
    ) {
        return inspect(user(authorization), query, intervalField, keyFields, deadline);
    }

    JsonNode inspect(
        Transport transport,
        VclDocument query,
        UUID intervalField,
        List<UUID> keyFields,
        long deadline
    ) {
        var spec = query.spec();
        requireSnapshot(query);
        if (
            keyFields.isEmpty() ||
            keyFields.size() > 8 ||
            Set.copyOf(keyFields).size() != keyFields.size()
        ) {
            throw new VclException("ASOF_FIELDS_INVALID", "");
        }
        UUID dataset = UUID.fromString(spec.at("/target/datasetId").textValue());
        var request = JsonNodeFactory.instance
            .objectNode()
            .put("intervalFieldId", intervalField.toString());
        request.set("document", query.json());
        var fields = request.putArray("keyFieldIds");
        keyFields.forEach(field -> fields.add(field.toString()));
        try {
            var body = VclCanonical.encode(request).getBytes(StandardCharsets.UTF_8);
            var manifest = parser.readJson(transport.manifest(dataset, body, deadline));
            verifyIdentity(spec, dataset, manifest);
            if (
                manifest.size() != 9 ||
                !manifest.path("dataVersion").isIntegralNumber() ||
                manifest.path("dataVersion").longValue() < 0 ||
                !digest(manifest, "schemaDigest") ||
                !digest(manifest, "accessScopeDigest") ||
                !digest(manifest, "queryDigest")
            ) throw invalid();
            var generation = manifest.path("generation").asText();
            try {
                if (!UUID.fromString(generation).toString().equals(generation)) throw invalid();
            } catch (IllegalArgumentException failure) {
                throw invalid();
            }
            return manifest;
        } catch (ConfigurableOwnerReads.Failure failure) {
            throw new VclException(failure.code(), "");
        }
    }

    private static boolean digest(JsonNode value, String field) {
        return (
            value.path(field).isTextual() && value.path(field).textValue().matches("[0-9a-f]{64}")
        );
    }

    private static void requireSnapshot(VclDocument query) {
        if (
            query.kind() != VclDocument.Kind.QUERY ||
            !"SNAPSHOT".equals(query.spec().at("/read/mode").textValue()) ||
            !query.spec().path("cursor").isNull()
        ) throw new VclException("ASOF_SNAPSHOT_REQUIRED", "");
    }

    private static void verifyIdentity(JsonNode spec, UUID dataset, JsonNode manifest) {
        if (
            !manifest.isObject() ||
            !"SNAPSHOT".equals(manifest.path("consistency").textValue()) ||
            !dataset.toString().equals(manifest.path("datasetId").textValue()) ||
            !spec
                .at("/read/snapshotId")
                .textValue()
                .equals(manifest.path("snapshotId").textValue()) ||
            !manifest.path("schemaRevision").isIntegralNumber() ||
            spec.at("/target/schemaRevision").longValue() !=
                manifest.path("schemaRevision").longValue()
        ) {
            throw new VclException("METADATA_SNAPSHOT_CHANGED", "");
        }
    }

    public JsonNode lookup(
        String authorization,
        VclCompilation.Node node,
        VclDocument query,
        UUID intervalField,
        Map<UUID, JsonNode> eventFields,
        Instant eventTime,
        JsonNode expectedManifest,
        long deadline
    ) {
        return lookup(
            user(authorization),
            node,
            query,
            intervalField,
            eventFields,
            eventTime,
            expectedManifest,
            deadline
        );
    }

    JsonNode lookup(
        Transport transport,
        VclCompilation.Node node,
        VclDocument query,
        UUID intervalField,
        Map<UUID, JsonNode> eventFields,
        Instant eventTime,
        JsonNode expectedManifest,
        long deadline
    ) {
        if (!"lookup_asof".equals(node.method())) throw new VclException(
            "ASOF_OPERATOR_REQUIRED",
            ""
        );
        var config = node.config();
        var spec = query.spec();
        if (!config.path("target").equals(spec.path("target"))) throw new VclException(
            "ASOF_PROJECTION_CONFLICT",
            ""
        );
        var keys = new LinkedHashMap<UUID, JsonNode>();
        var projection = new java.util.HashSet<String>();
        config.path("select").forEach(field -> projection.add(field.textValue()));
        projection.add(intervalField.toString());
        for (var pair : config.path("keyPairs")) {
            var field = UUID.fromString(pair.path("recordFieldId").textValue());
            var value = eventFields.get(UUID.fromString(pair.path("eventFieldId").textValue()));
            if (value == null || "NULL".equals(value.path("type").textValue())) {
                throw new VclException("ASOF_KEY_UNKNOWN", "");
            }
            keys.put(field, value);
            projection.add(field.toString());
        }
        var selected = new java.util.HashSet<String>();
        spec.path("select").forEach(field -> selected.add(field.textValue()));
        if (!selected.equals(projection)) throw new VclException("ASOF_PROJECTION_CONFLICT", "");
        var temporal = config.path("temporal");
        boolean split = "INTERVAL_SPLIT".equals(temporal.path("mode").textValue());
        Instant from = split ? eventInstant(eventFields, temporal.path("startFieldId")) : eventTime;
        Instant end = split ? eventInstant(eventFields, temporal.path("endFieldId")) : null;
        return read(transport, query, intervalField, keys, from, end, expectedManifest, deadline);
    }

    private static Instant eventInstant(Map<UUID, JsonNode> fields, JsonNode fieldId) {
        var value = fields.get(UUID.fromString(fieldId.textValue()));
        if (value == null || !"INSTANT".equals(value.path("type").textValue())) {
            throw new VclException("ASOF_TIME_INVALID", "");
        }
        return timestamp(value.path("value"));
    }

    public JsonNode read(
        String authorization,
        VclDocument query,
        UUID intervalField,
        Map<UUID, JsonNode> keys,
        Instant start,
        Instant end,
        JsonNode expectedManifest,
        long deadline
    ) {
        return read(
            user(authorization),
            query,
            intervalField,
            keys,
            start,
            end,
            expectedManifest,
            deadline
        );
    }

    JsonNode read(
        Transport transport,
        VclDocument query,
        UUID intervalField,
        Map<UUID, JsonNode> keys,
        Instant start,
        Instant end,
        JsonNode expectedManifest,
        long deadline
    ) {
        var spec = query.spec();
        requireSnapshot(query);
        if (
            keys.isEmpty() ||
            keys.size() > 8 ||
            start == null ||
            (end != null && !start.isBefore(end))
        ) {
            throw new VclException("ASOF_SNAPSHOT_REQUIRED", "");
        }
        UUID dataset = UUID.fromString(spec.at("/target/datasetId").textValue());
        var request = JsonNodeFactory.instance
            .objectNode()
            .put("intervalFieldId", intervalField.toString());
        request.set("document", query.json());
        var keyArray = request.putArray("keys");
        keys.forEach((id, value) ->
            keyArray.addObject().put("fieldId", id.toString()).set("literal", value)
        );
        if (end == null) request.put("eventTime", start.toString());
        else request.put("start", start.toString()).put("end", end.toString());
        try {
            var body = VclCanonical.encode(request).getBytes(StandardCharsets.UTF_8);
            var result = parser.readJson(transport.rows(dataset, body, deadline, end != null));
            var manifest = result.path("readManifest");
            verifyIdentity(spec, dataset, manifest);
            if (!manifest.equals(expectedManifest)) {
                throw new VclException("METADATA_SNAPSHOT_CHANGED", "");
            }
            if (end == null) validateOutcome(result);
            else validateSegments(result.path("segments"), start, end);
            return result;
        } catch (ConfigurableOwnerReads.Failure failure) {
            throw new VclException(failure.code(), "");
        }
    }

    Transport user(String authorization) {
        return new Transport() {
            public byte[] manifest(UUID dataset, byte[] body, long deadline) {
                return reads.datasetAsOfManifest(authorization, dataset, body, deadline);
            }

            public byte[] rows(UUID dataset, byte[] body, long deadline, boolean split) {
                return reads.datasetAsOf(authorization, dataset, body, deadline, split);
            }
        };
    }

    private static void validateSegments(JsonNode segments, Instant start, Instant end) {
        if (!segments.isArray() || segments.isEmpty() || segments.size() > 513) throw invalid();
        Instant next = start;
        for (var segment : segments) {
            Instant from = timestamp(segment.path("start"));
            Instant until = timestamp(segment.path("end"));
            if (!next.equals(from) || !until.isAfter(from) || until.isAfter(end)) throw invalid();
            validateOutcome(segment);
            next = until;
        }
        if (!next.equals(end)) throw invalid();
    }

    private static Instant timestamp(JsonNode value) {
        if (!value.isTextual()) throw invalid();
        try {
            var time = Instant.parse(value.textValue());
            if (
                time.getNano() % 1000 != 0 || !time.toString().equals(value.textValue())
            ) throw invalid();
            return time;
        } catch (DateTimeParseException failure) {
            throw invalid();
        }
    }

    private static void validateOutcome(JsonNode value) {
        var row = value.path("row");
        var reason = value.path("reason");
        if (
            "KNOWN".equals(value.path("status").textValue()) && row.isObject() && reason.isNull()
        ) return;
        if (
            "UNKNOWN".equals(value.path("status").textValue()) &&
            row.isNull() &&
            reason.isTextual() &&
            UNKNOWN_REASONS.contains(reason.textValue())
        ) return;
        throw invalid();
    }

    private static VclException invalid() {
        return new VclException("OWNER_SNAPSHOT_INVALID", "");
    }
}
