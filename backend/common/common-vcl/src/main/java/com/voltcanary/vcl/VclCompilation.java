package com.voltcanary.vcl;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Static typed IR only. Owner reauthorization, runtime readiness and bundle persistence are later stages. */
public record VclCompilation(
    VclDocument.Kind kind,
    String canonicalDocument,
    String documentDigest,
    String semanticDigest,
    String registryVersion,
    String compilerVersion,
    String registryDigest,
    String unitRegistryDigest,
    String accessScopeDigest,
    List<Dependency> dependencies,
    List<Node> graph
) {
    public record Dependency(
        VclMetadata.Resource kind,
        UUID id,
        long revision,
        String snapshotDigest
    ) {}

    public record Node(
        String id,
        String method,
        Map<String, String> inputs,
        com.fasterxml.jackson.databind.JsonNode config,
        String outputKind,
        Map<String, VclType> fields,
        String sessionId
    ) {
        public Node {
            inputs = Map.copyOf(inputs);
            config = config.deepCopy();
            fields = Map.copyOf(fields);
        }

        @Override
        public com.fasterxml.jackson.databind.JsonNode config() {
            return config.deepCopy();
        }

        @Override
        public String toString() {
            return "Node[method=" + method + ", outputKind=" + outputKind + "]";
        }
    }

    public VclCompilation {
        dependencies = List.copyOf(dependencies);
        graph = List.copyOf(graph);
    }

    public String stage() {
        return "STATIC_TYPED_IR";
    }

    @Override
    public String toString() {
        return (
            "VclCompilation[kind=" +
            kind +
            ", stage=" +
            stage() +
            ", documentDigest=" +
            documentDigest +
            "]"
        );
    }
}
