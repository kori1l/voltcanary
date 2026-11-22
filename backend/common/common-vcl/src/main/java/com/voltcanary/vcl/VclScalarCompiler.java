package com.voltcanary.vcl;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Front end for owner-supplied record scopes; uses the document compiler's type checker. */
public final class VclScalarCompiler {

    private final VclParser parser;
    private final VclRegistry registry;
    private final VclUnits units;

    public VclScalarCompiler(VclParser parser, VclRegistry registry, VclUnits units) {
        this.parser = parser;
        this.registry = registry;
        this.units = units;
    }

    public VclScalarProgram compile(
        String json,
        Map<UUID, VclType> authorizedFields,
        VclMetadata metadata
    ) {
        VclException.require(metadata != null, "ACCESS_SCOPE_REQUIRED", "");
        String scope = metadata.accessScopeDigest();
        VclException.require(
            scope != null && scope.matches("[0-9a-f]{64}"),
            "ACCESS_SCOPE_REQUIRED",
            ""
        );
        var fields = new LinkedHashMap<String, VclType>();
        var fieldSnapshot = Map.copyOf(authorizedFields);
        fieldSnapshot.forEach((id, type) -> fields.put(id.toString(), type));
        var expression = parser.expression(json);
        var references = new LinkedHashMap<UUID, VclType>();
        java.util.function.Function<UUID, VclType> resolve = id ->
            references.computeIfAbsent(id, key -> {
                VclException.require(
                    metadata.permits(VclMetadata.Resource.REFERENCE, key),
                    "RESOURCE_UNAVAILABLE",
                    ""
                );
                return metadata
                    .referenceType(key)
                    .orElseThrow(() -> new VclException("REF_TYPE_UNAVAILABLE", ""));
            });
        // The complete tree is checked once before constructing any executable IR.
        var types = new VclExpressionTypes(registry, units, resolve);
        types.infer(expression, fields, "", 1);
        var root = node(expression, types);
        var usedFields = new LinkedHashMap<UUID, VclType>();
        collectFields(root, usedFields);
        VclException.require(
            scope.equals(metadata.accessScopeDigest()),
            "ACCESS_SCOPE_CHANGED",
            ""
        );
        return new VclScalarProgram(root, usedFields, registry.digest(), units.digest(), scope);
    }

    private static void collectFields(VclScalarProgram.Node node, Map<UUID, VclType> fields) {
        if (node.fieldId() != null) fields.put(node.fieldId(), node.type());
        node.arguments().forEach(child -> collectFields(child, fields));
    }

    private VclScalarProgram.Node node(JsonNode expression, VclExpressionTypes types) {
        var type = types.inferredType(expression);
        return switch (expression.path("op").asText()) {
            case "CONST" -> new VclScalarProgram.Node(
                "CONST",
                type,
                null,
                expression.path("literal"),
                List.of()
            );
            case "FIELD" -> new VclScalarProgram.Node(
                "FIELD",
                type,
                UUID.fromString(expression.path("fieldId").asText()),
                null,
                List.of()
            );
            default -> {
                var arguments = new ArrayList<VclScalarProgram.Node>();
                for (var argument : expression.path("args")) arguments.add(node(argument, types));
                yield new VclScalarProgram.Node(
                    expression.path("method").asText(),
                    type,
                    null,
                    null,
                    arguments
                );
            }
        };
    }
}
