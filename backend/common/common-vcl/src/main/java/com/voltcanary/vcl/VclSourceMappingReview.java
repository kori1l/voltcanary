package com.voltcanary.vcl;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

/** Canonical identity of a source mapping reviewed through the owner API. */
public final class VclSourceMappingReview {
    private VclSourceMappingReview() { }

    public static String digest(String definitionDigest, String accessScopeDigest, UUID fieldId, JsonNode conversion) {
        return VclCanonical.sha256("SOURCE_MAPPING_PREVIEW/1\n" + definitionDigest + "\n"
                + accessScopeDigest + "\n" + fieldId + "\n" + VclCanonical.encode(conversion));
    }

    public static JsonNode convert(String type, JsonNode conversion, JsonNode sample) {
        if ("BOOLEAN".equals(type) && !sample.isBoolean() || "TEXT".equals(type)
                && (!sample.isTextual() || sample.textValue().codePointCount(0, sample.textValue().length()) > 512)) {
            throw new VclException("MAPPING_SAMPLE_TYPE_MISMATCH", "/sample");
        }
        var result = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        if (conversion.isObject() && conversion.size() == 1 && "identity".equals(conversion.path("method").textValue())) {
            return result.put("type", type).set("value", sample.deepCopy());
        }
        if (!"TEXT".equals(type)) throw new VclException("CONVERSION_TYPE_MISMATCH", "/conversion");
        var tokens = VclBooleanTokens.parse(conversion, "/conversion");
        return result.put("type", "BOOLEAN").put("value", tokens.evaluate(sample.textValue(), "/sample"));
    }
}
