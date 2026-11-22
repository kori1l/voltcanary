package com.voltcanary.vcl;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Immutable, bounded, statically checked scalar IR. Not an execution grant. */
public final class VclScalarProgram {

    public static final class Node {

        private final String operation;
        private final VclType type;
        private final UUID fieldId;
        private final JsonNode literal;
        private final List<Node> arguments;

        Node(String operation, VclType type, UUID fieldId, JsonNode literal, List<Node> arguments) {
            this.operation = operation;
            this.type = type;
            this.fieldId = fieldId;
            this.literal = literal == null ? null : literal.deepCopy();
            this.arguments = List.copyOf(arguments);
        }

        public String operation() {
            return operation;
        }

        public VclType type() {
            return type;
        }

        public UUID fieldId() {
            return fieldId;
        }

        public JsonNode literal() {
            return literal == null ? null : literal.deepCopy();
        }

        public List<Node> arguments() {
            return arguments;
        }
    }

    private final Node root;
    private final Map<UUID, VclType> fields;
    private final String registryDigest;
    private final String unitDigest;
    private final String scopeDigest;

    VclScalarProgram(
        Node root,
        Map<UUID, VclType> fields,
        String registryDigest,
        String unitDigest,
        String scopeDigest
    ) {
        this.root = root;
        this.fields = Map.copyOf(fields);
        this.registryDigest = registryDigest;
        this.unitDigest = unitDigest;
        this.scopeDigest = scopeDigest;
    }

    public Node root() {
        return root;
    }

    public Map<UUID, VclType> fields() {
        return fields;
    }

    public String registryDigest() {
        return registryDigest;
    }

    public String unitDigest() {
        return unitDigest;
    }

    public String scopeDigest() {
        return scopeDigest;
    }
}
