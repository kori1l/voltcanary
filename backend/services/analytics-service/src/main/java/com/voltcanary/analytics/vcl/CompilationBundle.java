package com.voltcanary.analytics.vcl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclCompilation;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclMetadata;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.UUID;

/** Sealed static IR plus owner pins. It is neither a review nor permission to activate. */
public final class CompilationBundle {
    public static final int MAX_BYTES = 8_388_608;
    public static final String VERSION = "vcl-owner-compiled-bundle-1";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final com.voltcanary.vcl.VclParser PARSER = new com.voltcanary.vcl.VclParser();
    private final UUID accountId;
    private final UUID id;
    private final String payload;
    private final String digest;
    private final CompilationOwnerValidation ownerValidation;
    private final VclCompilation compilation;

    private CompilationBundle(UUID accountId, UUID id, String payload, String digest, CompilationOwnerValidation ownerValidation,
            VclCompilation compilation) {
        this.accountId = java.util.Objects.requireNonNull(accountId);
        this.id = java.util.Objects.requireNonNull(id);
        this.payload = payload;
        this.digest = digest;
        this.ownerValidation = ownerValidation;
        this.compilation = compilation;
    }

    public static CompilationBundle seal(UUID accountId, UUID id, VclCompilation compilation, CompilationOwnerValidation validation) {
        if (compilation.kind() != com.voltcanary.vcl.VclDocument.Kind.RULE) throw new VclException("RULE_REQUIRED", "");
        var parsed = PARSER.parse(compilation.canonicalDocument());
        JsonNode document = parsed.json();
        if (!VclCanonical.json(parsed).equals(compilation.canonicalDocument())
                || !VclCanonical.documentDigest(parsed).equals(compilation.documentDigest())
                || !VclCanonical.semanticDigest(parsed).equals(compilation.semanticDigest())) throw new VclException("BUNDLE_INVALID", "");
        verifyGraph(document.path("spec"), compilation);
        verifyDependencies(compilation);
        validation.verify(compilation, UUID.fromString(document.at("/spec/executionGrantId").asText()));
        var root = MAPPER.createObjectNode().put("version", VERSION).put("stage", "OWNER_VALIDATED_IR")
                .put("accountId", accountId.toString()).put("bundleId", id.toString());
        JsonNode typed = MAPPER.valueToTree(compilation);
        for (var node : typed.path("graph")) {
            node.path("fields").forEach(field -> {
                if (field.path("enumValues").isArray()) {
                    var values = new java.util.ArrayList<String>();
                    field.path("enumValues").forEach(value -> values.add(value.asText()));
                    var sorted = ((com.fasterxml.jackson.databind.node.ObjectNode) field).putArray("enumValues");
                    values.stream().sorted().forEach(sorted::add);
                }
            });
        }
        root.set("compilation", typed);
        root.set("ownerValidation", MAPPER.valueToTree(validation));
        String payload = VclCanonical.encode(root);
        if (payload.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw new VclException("BUNDLE_SIZE", "");
        return new CompilationBundle(accountId, id, payload, VclCanonical.sha256(payload), validation, compilation);
    }

    private static void verifyGraph(JsonNode spec, VclCompilation compilation) {
        var declarations = new HashMap<String, JsonNode>();
        for (var declaration : spec.path("nodes")) {
            if (declarations.putIfAbsent(declaration.path("id").textValue(), declaration) != null) {
                throw new VclException("BUNDLE_GRAPH_CHANGED", "");
            }
        }
        var resolved = new HashSet<String>();
        for (var node : compilation.graph()) {
            var declaration = declarations.remove(node.id());
            if (declaration == null || !node.method().equals(declaration.path("method").textValue())
                    || !VclCanonical.encode(node.config()).equals(VclCanonical.encode(declaration.path("config")))
                    || !MAPPER.valueToTree(node.inputs()).equals(declaration.path("inputs"))
                    || !resolved.containsAll(node.inputs().values())) {
                throw new VclException("BUNDLE_GRAPH_CHANGED", "");
            }
            resolved.add(node.id());
        }
        if (!declarations.isEmpty() || compilation.graph().isEmpty()
                || !compilation.graph().getLast().id().equals(spec.path("terminal").textValue())) {
            throw new VclException("BUNDLE_GRAPH_CHANGED", "");
        }
    }

    private record DependencyTarget(VclMetadata.Resource kind, UUID id, long revision) { }

    private static void verifyDependencies(VclCompilation compilation) {
        var targets = new HashSet<DependencyTarget>();
        for (var node : compilation.graph()) {
            if (node.method().equals("read_stream")) {
                var config = node.config();
                targets.add(new DependencyTarget(VclMetadata.Resource.BINDING,
                        UUID.fromString(config.path("bindingId").textValue()), config.path("schemaRevision").longValue()));
            } else if (node.method().equals("lookup_asof")) {
                var target = node.config().path("target");
                targets.add(new DependencyTarget(VclMetadata.Resource.DATASET,
                        UUID.fromString(target.path("datasetId").textValue()), target.path("schemaRevision").longValue()));
            }
        }
        for (var dependency : compilation.dependencies()) {
            if (!targets.remove(new DependencyTarget(dependency.kind(), dependency.id(), dependency.revision()))
                    || dependency.snapshotDigest() == null || !dependency.snapshotDigest().matches("[0-9a-f]{64}")) {
                throw new VclException("BUNDLE_DEPENDENCIES_CHANGED", "");
            }
        }
        if (!targets.isEmpty()) throw new VclException("BUNDLE_DEPENDENCIES_CHANGED", "");
    }

    /** Hydrates only sealed data. Execution must separately resolve its pinned compiler/operator versions. */
    public static CompilationBundle restore(UUID accountId, UUID id, String payload, String digest) {
        if (payload == null || payload.length() > MAX_BYTES || payload.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES
                || !VclCanonical.sha256(payload).equals(digest)) throw new VclException("BUNDLE_INTEGRITY", "");
        try {
            JsonNode json = MAPPER.readTree(payload);
            if (!json.isObject() || json.size() != 6 || !json.has("compilation") || !json.has("ownerValidation")
                    || !VERSION.equals(json.path("version").asText()) || !"OWNER_VALIDATED_IR".equals(json.path("stage").asText())
                    || !accountId.toString().equals(json.path("accountId").asText())
                    || !id.toString().equals(json.path("bundleId").asText())) throw new VclException("BUNDLE_INTEGRITY", "");
            var compilation = MAPPER.treeToValue(json.path("compilation"), VclCompilation.class);
            var validation = MAPPER.treeToValue(json.path("ownerValidation"), CompilationOwnerValidation.class);
            var restored = seal(accountId, id, compilation, validation);
            if (!restored.payload().equals(payload)) throw new VclException("BUNDLE_INTEGRITY", "");
            return restored;
        } catch (IOException | RuntimeException error) { throw new VclException("BUNDLE_INTEGRITY", ""); }
    }

    public UUID accountId() { return accountId; }
    public UUID id() { return id; }
    public String payload() { return payload; }
    public String digest() { return digest; }

    public java.util.List<VclCompilation.Dependency> dependencies() {
        return java.util.List.copyOf(compilation.dependencies());
    }

    /** Return a detached view of the verified bundle envelope. */
    public JsonNode json() {
        try {
            return MAPPER.readTree(payload);
        } catch (IOException invalid) {
            throw new VclException("BUNDLE_INTEGRITY", "");
        }
    }

    /** A reviewed start must use the boundary and input generations sealed at compilation. */
    public void verifyStartInputs(java.time.Instant boundary, java.util.Map<UUID, UUID> bindingGenerations) {
        var execution = ownerValidation.execution();
        if (!java.time.Instant.parse(execution.startBoundary()).equals(boundary)
                || !execution.bindingGenerations().equals(bindingGenerations)) {
            throw new VclException("BUNDLE_START_INPUTS_CHANGED", "");
        }
    }

    public UUID inputGeneration(UUID binding) {
        var generation = ownerValidation.execution().bindingGenerations().get(binding);
        if (generation == null) throw new VclException("WORKER_SOURCE_NOT_PINNED", "");
        return generation;
    }

    public void verifyStartCheckpoint(String checkpointDigest) {
        var execution = ownerValidation.execution();
        if (execution.resourceRevisions().stream().anyMatch(pin ->
                pin.kind() == CompilationOwnerValidation.ResourceKind.RULE_START_CHECKPOINT)
                && !execution.snapshotSetDigest().equals(checkpointDigest)) {
            throw new VclException("BUNDLE_START_INPUTS_CHANGED", "");
        }
    }

    public void verifyCompiledDependencies(VclCompilation fresh) {
        if (!compilation.compilerVersion().equals(fresh.compilerVersion())
                || !compilation.registryVersion().equals(fresh.registryVersion())
                || !compilation.registryDigest().equals(fresh.registryDigest())
                || !compilation.unitRegistryDigest().equals(fresh.unitRegistryDigest())) {
            throw new VclException("BUNDLE_COMPILER_PROFILE_CHANGED", "");
        }
        if (!compilation.documentDigest().equals(fresh.documentDigest())
                || !new HashSet<>(compilation.dependencies()).equals(new HashSet<>(fresh.dependencies()))) {
            throw new VclException("BUNDLE_DEPENDENCIES_CHANGED", "");
        }
    }

    CompilationOwnerValidation ownerValidation() { return ownerValidation; }

    public void verifyReviewedOwnerPins(CompilationOwnerValidation.ResourcePin grant,
            CompilationOwnerValidation.ResourcePin policy) {
        var pins = ownerValidation.execution().resourceRevisions();
        if (!pins.contains(grant) || !pins.contains(policy)) {
            throw new VclException("BUNDLE_OWNER_PINS_CHANGED", "");
        }
    }

    @Override public String toString() { return "CompilationBundle[id=" + id + ", digest=" + digest + "]"; }
}
