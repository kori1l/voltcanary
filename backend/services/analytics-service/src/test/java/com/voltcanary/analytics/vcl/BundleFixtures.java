package com.voltcanary.analytics.vcl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voltcanary.vcl.VclCompilation;
import com.voltcanary.vcl.VclCompiler;
import com.voltcanary.vcl.VclDocument;
import com.voltcanary.vcl.VclMetadata;
import com.voltcanary.vcl.VclParser;
import com.voltcanary.vcl.VclRegistry;
import com.voltcanary.vcl.VclUnits;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.UUID;

final class BundleFixtures {
    static final String SCOPE = "a".repeat(64);
    static final UUID GRANT = UUID.fromString("00000000-0000-4000-8000-000000000300");
    static final VclParser PARSER = new VclParser();
    static final VclCompiler COMPILER = new VclCompiler(new VclRegistry(PARSER), VclUnits.standard());
    static final JsonNode EXAMPLES;
    static {
        try (var input = BundleFixtures.class.getResourceAsStream("/vcl/configurable-operations-language.examples.json")) {
            EXAMPLES = new ObjectMapper().readTree(input);
        } catch (java.io.IOException error) { throw new IllegalStateException(error); }
    }

    static final VclMetadata METADATA = new VclMetadata() {
        public String accessScopeDigest() { return SCOPE; }
        public Optional<Snapshot> dataset(UUID id, long revision) { return find("datasets", id, revision, "revision"); }
        public Optional<Snapshot> binding(UUID id, long revision) { return find("bindings", id, revision, "schemaRevision"); }
        public boolean permits(Resource kind, UUID id) { return true; }
        private Optional<Snapshot> find(String group, UUID id, long revision, String field) {
            return java.util.stream.StreamSupport.stream(EXAMPLES.at("/fixtureRegistry/" + group).spliterator(), false)
                    .filter(value -> value.path("id").asText().equals(id.toString()) && value.path(field).asLong() == revision)
                    .map(value -> new Snapshot(id, revision, value)).findFirst();
        }
    };

    static VclDocument document(String name) {
        var json = EXAMPLES.at("/documents/4").deepCopy();
        ((ObjectNode) json.path("spec")).put("name", name);
        return PARSER.parse(json.toString());
    }

    static VclCompilation compilation(String name) { return COMPILER.compile(document(name), METADATA); }

    static CompilationOwnerValidation validation(VclCompilation compilation) {
        var checks = compilation.dependencies().stream().map(dependency -> new CompilationOwnerValidation.DependencyCheck(
                dependency, true, true, CompilationOwnerValidation.IndexReadiness.READY, "b".repeat(64))).toList();
        var versions = new LinkedHashMap<String, String>();
        var generations = new LinkedHashMap<UUID, UUID>();
        for (var node : compilation.graph()) {
            versions.put(node.method(), "fixture-operator-v1");
            if (node.method().equals("read_stream")) {
                generations.put(UUID.fromString(node.config().path("bindingId").asText()), UUID.fromString("10000000-0000-4000-8000-000000000001"));
            }
        }
        var pins = new CompilationOwnerValidation.ExecutionPins(GRANT, generations, "2026-11-03T00:00:00Z",
                "c".repeat(64), "d".repeat(64), java.util.List.of(
                        new CompilationOwnerValidation.ResourcePin(CompilationOwnerValidation.ResourceKind.EXECUTION_GRANT, GRANT, 1, "e".repeat(64)),
                        new CompilationOwnerValidation.ResourcePin(CompilationOwnerValidation.ResourceKind.EVIDENCE_POLICY,
                                UUID.fromString("00000000-0000-4000-8000-000000000302"), 1, "f".repeat(64))),
                Wgs84ScalarKernel.VERSION, versions);
        return new CompilationOwnerValidation(compilation.documentDigest(), SCOPE, checks, pins,
                new CompilationOwnerValidation.Cost(1024, 4096, 10, 100));
    }

    static CompilationBundle bundle(UUID accountId, UUID id, String name) {
        var compilation = compilation(name);
        return CompilationBundle.seal(accountId, id, compilation, validation(compilation));
    }

    private BundleFixtures() { }
}
