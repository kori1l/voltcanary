package com.voltcanary.device.configurable.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.voltcanary.device.configurable.port.SourceBindingCatalog;
import com.voltcanary.device.security.AuthenticatedUser;
import com.voltcanary.vcl.VclBooleanTokens;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclSourceNormalization;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Diagnostic preview tied to one authenticated source schema. */
@Service
public class SourceMappingPreviews {
    private final SourceBindingCatalog catalog;
    private final SourceMappingHistory history;
    private final ObjectMapper mapper;

    public SourceMappingPreviews(SourceBindingCatalog catalog, SourceMappingHistory history, ObjectMapper mapper) {
        this.catalog = catalog;
        this.history = history;
        this.mapper = mapper;
    }

    public record Preview(UUID bindingId, long schemaRevision, UUID schemaSnapshotId, String mappingDigest, JsonNode literal) { }

    public Preview preview(AuthenticatedUser actor, UUID id, long revision, UUID fieldId, JsonNode conversion, JsonNode sample) {
        var source = catalog.describe(actor.id(), id, revision);
        if (!source.sourceActive()) throw new VclException("SOURCE_NOT_ACTIVE", "");
        if (source.currentSchemaRevision() != revision) throw new VclException("BINDING_REVISION_CHANGED", "");
        String type = inputType(source, fieldId, history.conversion(source, fieldId).isPresent());
        JsonNode result = convert(type, conversion, sample);
        String digest = digest(source, fieldId, conversion);
        if (!source.equals(catalog.describe(actor.id(), id, revision))) throw new VclException("METADATA_SNAPSHOT_CHANGED", "");
        return new Preview(id, revision, source.snapshotId(), digest, result);
    }

    public record Replay(UUID bindingId, long schemaRevision, UUID schemaSnapshotId, String definitionDigest, JsonNode literal) { }
    public record EventReplay(UUID bindingId, UUID deviceId, long schemaRevision, UUID schemaSnapshotId,
            String definitionDigest, Map<String, Object> metrics) { }

    public EventReplay replayEvent(AuthenticatedUser actor, UUID id, long revision, UUID snapshotId, JsonNode metrics) {
        if (!metrics.isObject() || metrics.size() > 128
                || java.util.stream.StreamSupport.stream(metrics.spliterator(), false).anyMatch(JsonNode::isNull)) {
            throw new VclException("SOURCE_NORMALIZATION_INVALID", "/metrics");
        }
        var source = catalog.describe(actor.id(), id, revision);
        if (!source.sourceActive()) throw new VclException("SOURCE_NOT_ACTIVE", "");
        if (!snapshotId.equals(source.snapshotId())) throw new VclException("METADATA_SNAPSHOT_CHANGED", "");
        var normalized = VclSourceNormalization.normalize(objectMap(metrics), objectMap(catalog.normalization(source)));
        if (!source.equals(catalog.describe(actor.id(), id, revision))) throw new VclException("METADATA_SNAPSHOT_CHANGED", "");
        return new EventReplay(id, source.deviceId(), revision, source.snapshotId(), source.definitionDigest(), normalized);
    }

    private Map<String, Object> objectMap(JsonNode value) {
        return mapper.convertValue(value, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
    }

    public Replay replay(AuthenticatedUser actor, UUID id, long revision, UUID fieldId, JsonNode sample) {
        var source = catalog.describe(actor.id(), id, revision);
        if (!source.sourceActive()) throw new VclException("SOURCE_NOT_ACTIVE", "");
        var reviewed = history.conversion(source, fieldId);
        var identity = JsonNodeFactory.instance.objectNode().put("method", "identity");
        JsonNode result = convert(inputType(source, fieldId, reviewed.isPresent()), reviewed.orElse(identity), sample);
        if (!source.equals(catalog.describe(actor.id(), id, revision))) throw new VclException("METADATA_SNAPSHOT_CHANGED", "");
        return new Replay(id, revision, source.snapshotId(), source.definitionDigest(), result);
    }

    private static String inputType(com.voltcanary.vcl.VclSourceBindingDescription source, UUID field, boolean reviewed) {
        for (var candidate : source.definition().path("fields")) {
            if (field.toString().equals(candidate.path("id").textValue())) {
                return reviewed ? "TEXT" : candidate.path("type").textValue();
            }
        }
        throw new VclException("MAPPING_FIELD_UNAVAILABLE", "");
    }

    static String digest(com.voltcanary.vcl.VclSourceBindingDescription source, UUID fieldId, JsonNode conversion) {
        return digest(source.definitionDigest(), source.accessScopeDigest(), fieldId, conversion);
    }

    static String digest(String definitionDigest, String scope, UUID fieldId, JsonNode conversion) {
        return VclCanonical.sha256("SOURCE_MAPPING_PREVIEW/1\n" + definitionDigest + "\n"
                + scope + "\n" + fieldId + "\n" + VclCanonical.encode(conversion));
    }

    private static JsonNode convert(String type, JsonNode conversion, JsonNode sample) {
        if ("BOOLEAN".equals(type) && !sample.isBoolean() || "TEXT".equals(type)
                && (!sample.isTextual() || sample.textValue().codePointCount(0, sample.textValue().length()) > 512)) {
            throw new VclException("MAPPING_SAMPLE_TYPE_MISMATCH", "/sample");
        }
        var result = JsonNodeFactory.instance.objectNode();
        if (conversion.isObject() && conversion.size() == 1 && "identity".equals(conversion.path("method").textValue())) {
            return result.put("type", type).set("value", sample);
        }
        if (!"TEXT".equals(type)) throw new VclException("CONVERSION_TYPE_MISMATCH", "/conversion");
        var tokens = VclBooleanTokens.parse(conversion, "/conversion");
        return result.put("type", "BOOLEAN").put("value", tokens.evaluate(sample.textValue(), "/sample"));
    }
}
