package com.voltcanary.vcl;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

public final class VclParser {
    public static final int MAX_BYTES = 1_048_576;
    public static final int MAX_DEPTH = 64;
    private final ObjectMapper mapper;
    private final JsonNode grammar;
    private final JsonSchema validator;
    private final JsonSchema expressionValidator;
    private final JsonSchema literalValidator;

    public VclParser() {
        var factory = JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(MAX_DEPTH)
                        .maxStringLength(MAX_BYTES).maxNumberLength(64).build()).build();
        mapper = new ObjectMapper(factory);
        try (var stream = VclParser.class.getResourceAsStream("/vcl/configurable-operations-language.schema.json")) {
            if (stream == null) throw new IllegalStateException("VCL grammar resource missing");
            grammar = mapper.readTree(stream);
            rejectRemoteReferences(grammar);
            validator = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7).getSchema(grammar);
            expressionValidator = fragment("Expression");
            literalValidator = fragment("Literal");
        } catch (IOException error) {
            throw new IllegalStateException("VCL grammar resource unreadable", error);
        }
    }

    public VclDocument parse(String json) {
        VclException.require(json != null, "INVALID_JSON", "");
        VclException.require(json.length() <= MAX_BYTES, "PAYLOAD_SIZE", "");
        validUnicode(json, "");
        return parse(json.getBytes(StandardCharsets.UTF_8));
    }

    public VclDocument parse(byte[] utf8) {
        return new VclDocument(read(utf8, validator));
    }

    JsonNode expression(String json) {
        VclException.require(json != null && json.length() <= MAX_BYTES, "PAYLOAD_SIZE", "");
        validUnicode(json, "");
        return VclValues.normalized(read(json.getBytes(StandardCharsets.UTF_8), expressionValidator));
    }

    /** Validates a typed wire literal using the same closed grammar as documents. */
    public JsonNode literal(String json) {
        VclException.require(json != null && json.length() <= MAX_BYTES, "PAYLOAD_SIZE", "");
        validUnicode(json, "");
        return VclValues.normalized(read(json.getBytes(StandardCharsets.UTF_8), literalValidator));
    }

    private JsonSchema fragment(String name) {
        var root = mapper.createObjectNode();
        root.put("$ref", "#/$defs/" + name);
        root.set("$defs", grammar.path("$defs"));
        return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7).getSchema(root);
    }

    private JsonNode read(byte[] utf8, JsonSchema schema) {
        VclException.require(utf8 != null && utf8.length <= MAX_BYTES, "PAYLOAD_SIZE", "");
        String json;
        try {
            json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(utf8)).toString();
        } catch (CharacterCodingException error) {
            throw new VclException("INVALID_UTF8", "");
        }
        try (var parser = mapper.createParser(json)) {
            JsonNode document = mapper.readTree(parser);
            VclException.require(document != null && parser.nextToken() == null, "INVALID_JSON", "");
            validateTree(document, "");
            if (schema != null) {
                var errors = schema.validate(document);
                if (!errors.isEmpty()) {
                    String path = errors.stream().map(e -> e.getInstanceLocation().toString()).sorted().findFirst().orElse("");
                    throw new VclException("GRAMMAR", path);
                }
            }
            return document;
        } catch (IOException error) {
            throw new VclException("INVALID_JSON", "");
        }
    }

    public JsonNode grammar() { return grammar.deepCopy(); }

    /** Strict JSON for closed owner envelopes; callers must separately validate their allowed fields. */
    public JsonNode readJson(byte[] utf8) { return read(utf8, null); }

    private static void rejectRemoteReferences(JsonNode node) {
        if (node.has("$ref") && !node.path("$ref").asText().startsWith("#/")) {
            throw new IllegalStateException("VCL grammar cannot resolve external schemas");
        }
        node.forEach(VclParser::rejectRemoteReferences);
    }

    private static void validateTree(JsonNode node, String path) {
        if (node.isTextual()) validUnicode(node.textValue(), path);
        if (node.isIntegralNumber()) {
            VclException.require(node.canConvertToLong() && node.longValue() >= -9_007_199_254_740_991L
                            && node.longValue() <= 9_007_199_254_740_991L,
                    "UNSAFE_JSON_NUMBER", path);
        }
        if (node.isFloatingPointNumber()) {
            VclException.require(Double.isFinite(node.doubleValue()) && Math.abs(node.doubleValue()) <= 9_007_199_254_740_991D,
                    "NUMBER_RANGE", path);
        }
        if (node.isObject()) {
            node.fields().forEachRemaining(e -> {
                validUnicode(e.getKey(), path);
                validateTree(e.getValue(), path + "/" + e.getKey().replace("~", "~0").replace("/", "~1"));
            });
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) validateTree(node.get(i), path + "/" + i);
        }
    }

    static void validUnicode(String value, String path) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                VclException.require(++i < value.length() && Character.isLowSurrogate(value.charAt(i)), "INVALID_UNICODE", path);
            } else VclException.require(!Character.isLowSurrogate(c), "INVALID_UNICODE", path);
        }
    }
}
