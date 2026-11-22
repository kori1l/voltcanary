package com.voltcanary.aiagent.orchestration.contract;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Canonical local validation is mandatory even for providers advertising strict output. */
@Component
public final class ProtocolValidator {

    public static final int MAX_BYTES = 1_048_576;
    private final ObjectMapper mapper;
    private final JsonNode canonical;
    private final JsonSchemaFactory factory = JsonSchemaFactory.getInstance(
        SpecVersion.VersionFlag.V202012
    );
    private final ConcurrentHashMap<String, com.networknt.schema.JsonSchema> validators =
        new ConcurrentHashMap<>();

    public ProtocolValidator(ObjectMapper mapper) {
        this.mapper = mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        try (
            var stream = getClass().getResourceAsStream(
                "/assistant-v2/ai-assistant-protocol.schema.json"
            )
        ) {
            if (stream == null) throw new IllegalStateException(
                "Canonical assistant protocol missing"
            );
            canonical = this.mapper.readTree(stream);
            try (
                var collaboration = getClass().getResourceAsStream(
                    "/assistant-v2/ai-assistant-collaboration.schema.json"
                )
            ) {
                if (collaboration == null) throw new IllegalStateException(
                    "Collaboration protocol missing"
                );
                var definitions = (ObjectNode) canonical.path("$defs");
                this.mapper
                    .readTree(collaboration)
                    .path("$defs")
                    .fields()
                    .forEachRemaining(entry -> {
                        var definition = entry.getValue().deepCopy();
                        namespaceCollaboration(definition);
                        definitions.set("collab." + entry.getKey(), definition);
                    });
            }
        } catch (IOException failure) {
            throw new IllegalStateException("Canonical assistant protocol unreadable", failure);
        }
    }

    private void namespaceCollaboration(JsonNode node) {
        if (node instanceof ObjectNode object && object.has("$ref")) {
            String reference = object.path("$ref").asText();
            if (!reference.startsWith("#/$defs/")) throw new IllegalArgumentException(
                "EXTERNAL_COLLABORATION_SCHEMA_REFERENCE"
            );
            object.put("$ref", "#/$defs/collab." + reference.substring("#/$defs/".length()));
        }
        if (node.isContainerNode()) node.forEach(this::namespaceCollaboration);
    }

    public JsonNode parse(String json) {
        if (json == null || json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) fail(
            "PAYLOAD_SIZE"
        );
        try (var parser = mapper.createParser(json)) {
            JsonNode node = mapper.readTree(parser);
            if (node == null || parser.nextToken() != null) fail("INVALID_JSON");
            bounded(node, 0);
            return node;
        } catch (IOException failure) {
            throw new IllegalArgumentException("INVALID_JSON");
        }
    }

    public void validate(String definition, JsonNode document) {
        bounded(document, 0);
        var schema = validators.computeIfAbsent(definition, key -> compile(projection(key)));
        if (!schema.validate(document).isEmpty()) fail("CONTRACT_INVALID:" + definition);
    }

    /** Schema-owned locations only: no offending values or provider output in diagnostics. */
    public String violations(String definition, JsonNode document) {
        var schema = validators.computeIfAbsent(definition, key -> compile(projection(key)));
        return schema
            .validate(document)
            .stream()
            .map(error -> error.getType() + ":" + error.getSchemaLocation())
            .distinct()
            .sorted()
            .limit(8)
            .collect(java.util.stream.Collectors.joining("; "));
    }

    public <T> T read(String definition, JsonNode document, Class<T> type) {
        validate(definition, document);
        try {
            return mapper.treeToValue(document, type);
        } catch (IOException failure) {
            throw new IllegalArgumentException("CONTRACT_BINDING:" + definition);
        }
    }

    /** Only the selected response contract and its transitive definitions go to the provider. */
    public ObjectNode projection(String definition) {
        if (!canonical.path("$defs").has(definition)) throw new IllegalArgumentException(
            "UNKNOWN_SCHEMA"
        );
        var result = mapper.createObjectNode();
        result.put("$schema", canonical.path("$schema").asText());
        result.put("$ref", "#/$defs/" + definition);
        var definitions = result.putObject("$defs");
        collect(definition, definitions);
        return result;
    }

    public void validateRegistered(JsonNode schema, JsonNode document) {
        bounded(document, 0);
        var errors = compile(schema).validate(document);
        if (!errors.isEmpty()) throw new RegisteredViolation(
            errors
                .stream()
                .map(error -> error.getType() + ":" + error.getSchemaLocation())
                .distinct()
                .sorted(
                    java.util.Comparator.comparingInt((String location) ->
                        location.contains("/$defs/stepResponse/oneOf/") ? 1 : 0
                    ).thenComparing(java.util.Comparator.naturalOrder())
                )
                .limit(8)
                .collect(java.util.stream.Collectors.joining("; "))
        );
    }

    public void validateOperationDocument(
        JsonNode schema,
        JsonNode document,
        JsonNode commandSchema,
        JsonNode operations
    ) {
        try {
            validateRegistered(schema, document);
        } catch (RegisteredViolation invalid) {
            var variants = commandSchema.at("/properties/operations/items/anyOf");
            if (operations != null && operations.isArray() && variants.isArray()) {
                for (int index = 0; index < operations.size(); index++) {
                    var operation = operations.get(index);
                    var matching = java.util.stream.StreamSupport.stream(
                        variants.spliterator(),
                        false
                    )
                        .filter(
                            variant ->
                                variant.at("/properties/op/enum/0").isTextual() &&
                                variant.at("/properties/op/enum/0").equals(operation.path("op"))
                        )
                        .toList();
                    if (
                        !matching.isEmpty() &&
                        operation.isObject() &&
                        matching
                            .stream()
                            .allMatch(
                                variant ->
                                    variant.path("additionalProperties").isBoolean() &&
                                    !variant.path("additionalProperties").booleanValue()
                            )
                    ) {
                        var fields = new java.util.HashSet<String>();
                        matching.forEach(variant ->
                            variant.path("properties").fieldNames().forEachRemaining(fields::add)
                        );
                        var actualFields = operation.fieldNames();
                        while (actualFields.hasNext()) {
                            if (
                                !fields.contains(actualFields.next())
                            ) throw new RegisteredViolation(
                                "operations[" +
                                    index +
                                    "]." +
                                    operation.path("op").asText() +
                                    ": additionalProperties:#/additionalProperties"
                            );
                        }
                    }
                    RegisteredViolation closest = null;
                    int fewestErrors = Integer.MAX_VALUE;
                    for (var variant : matching) {
                        try {
                            validateRegistered(variant, operation);
                            closest = null;
                            break;
                        } catch (RegisteredViolation operationError) {
                            int errors = operationError.locations().split("; ", -1).length;
                            if (errors < fewestErrors) {
                                fewestErrors = errors;
                                closest = operationError;
                            }
                        }
                    }
                    if (closest != null) throw new RegisteredViolation(
                        "operations[" +
                            index +
                            "]." +
                            operation.path("op").asText() +
                            ": " +
                            closest.locations()
                    );
                }
            }
            throw invalid;
        }
    }

    public static final class RegisteredViolation extends IllegalArgumentException {

        private final String locations;

        public RegisteredViolation(String locations) {
            super("REGISTERED_SCHEMA_INVALID");
            this.locations = locations;
        }

        public String locations() {
            return locations;
        }
    }

    private com.networknt.schema.JsonSchema compile(JsonNode schema) {
        return factory.getSchema(
            schema,
            SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build()
        );
    }

    private void collect(String name, ObjectNode definitions) {
        if (definitions.has(name)) return;
        JsonNode value = canonical.path("$defs").get(name);
        if (value == null) fail("UNKNOWN_SCHEMA");
        definitions.set(name, value.deepCopy());
        references(value, definitions);
    }

    private void references(JsonNode node, ObjectNode definitions) {
        if (node.has("$ref")) collect(
            node.path("$ref").asText().substring("#/$defs/".length()),
            definitions
        );
        if (node.isContainerNode()) node.forEach(child -> references(child, definitions));
    }

    private static void bounded(JsonNode value, int depth) {
        if (value == null || depth > 32) fail("PAYLOAD_DEPTH");
        if (
            depth == 0 && value.toString().getBytes(StandardCharsets.UTF_8).length > MAX_BYTES
        ) fail("PAYLOAD_SIZE");
        if (value.isContainerNode()) value.forEach(child -> bounded(child, depth + 1));
    }

    private static void fail(String code) {
        throw new IllegalArgumentException(code);
    }
}
