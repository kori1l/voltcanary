package com.voltcanary.analytics.vcl;

import com.voltcanary.vcl.VclCompilation;
import com.voltcanary.vcl.VclException;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Attestation from trusted owner adapters, never accepted directly as request JSON. */
public record CompilationOwnerValidation(
    String documentDigest,
    String accessScopeDigest,
    List<DependencyCheck> dependencies,
    ExecutionPins execution,
    Cost cost
) {
    public enum IndexReadiness {
        NOT_REQUIRED,
        READY,
        MISSING,
    }

    public enum ResourceKind {
        EXECUTION_GRANT,
        EVIDENCE_POLICY,
        SPATIAL_FEATURE,
        SOURCE_MAPPING,
        QUALITY_POLICY,
        REFERENCE_CHECKPOINT,
        RULE_START_CHECKPOINT,
    }

    public record ResourcePin(ResourceKind kind, UUID id, long revision, String snapshotDigest) {
        public ResourcePin {
            Objects.requireNonNull(kind);
            Objects.requireNonNull(id);
            if (revision < 1) throw new IllegalArgumentException("Invalid resource revision");
            digest(snapshotDigest);
        }
    }

    public record DependencyCheck(
        VclCompilation.Dependency dependency,
        boolean authorized,
        boolean ready,
        IndexReadiness indexReadiness,
        String capabilityDigest
    ) {
        public DependencyCheck {
            Objects.requireNonNull(dependency);
            Objects.requireNonNull(indexReadiness);
            digest(capabilityDigest);
        }
    }

    public record ExecutionPins(
        UUID grantId,
        Map<UUID, UUID> bindingGenerations,
        String startBoundary,
        String keyDefinitionDigest,
        String snapshotSetDigest,
        List<ResourcePin> resourceRevisions,
        String spatialKernelVersion,
        Map<String, String> operatorVersions
    ) {
        public ExecutionPins {
            Objects.requireNonNull(grantId);
            bindingGenerations = Map.copyOf(bindingGenerations);
            if (
                bindingGenerations.isEmpty() || bindingGenerations.size() > 64
            ) throw new IllegalArgumentException("Invalid input generations");
            Objects.requireNonNull(startBoundary);
            var boundary = Instant.parse(startBoundary);
            int year = boundary.atOffset(java.time.ZoneOffset.UTC).getYear();
            if (
                year < 1 || year > 9999 || boundary.getNano() % 1000 != 0
            ) throw new IllegalArgumentException("Invalid start boundary");
            startBoundary = boundary.toString();
            digest(keyDefinitionDigest);
            digest(snapshotSetDigest);
            resourceRevisions = resourceRevisions
                .stream()
                .sorted(
                    java.util.Comparator.comparing((ResourcePin pin) ->
                        pin.kind().name()
                    ).thenComparing(pin -> pin.id().toString())
                )
                .toList();
            if (
                resourceRevisions.isEmpty() || resourceRevisions.size() > 128
            ) throw new IllegalArgumentException("Invalid resource pins");
            Objects.requireNonNull(spatialKernelVersion);
            operatorVersions = Map.copyOf(operatorVersions);
            if (
                operatorVersions.isEmpty() ||
                operatorVersions.size() > 12 ||
                operatorVersions
                    .values()
                    .stream()
                    .anyMatch(value -> !value.matches("[A-Za-z0-9._/-]{1,128}"))
            ) {
                throw new IllegalArgumentException("Invalid operator versions");
            }
        }
    }

    public record Cost(
        long stateBytes,
        long stateBudgetBytes,
        long workUnits,
        long workBudgetUnits
    ) {
        public Cost {
            if (stateBytes < 0 || workUnits < 0 || stateBudgetBytes <= 0 || workBudgetUnits <= 0) {
                throw new IllegalArgumentException("Invalid owner cost estimate");
            }
        }
    }

    public CompilationOwnerValidation {
        digest(documentDigest);
        digest(accessScopeDigest);
        dependencies = dependencies
            .stream()
            .sorted(
                java.util.Comparator.comparing((DependencyCheck check) ->
                    check.dependency().kind().name()
                )
                    .thenComparing(check -> check.dependency().id().toString())
                    .thenComparingLong(check -> check.dependency().revision())
            )
            .toList();
        if (dependencies.size() > 128) throw new IllegalArgumentException(
            "Too many owner dependencies"
        );
        Objects.requireNonNull(execution);
        Objects.requireNonNull(cost);
    }

    public void verify(VclCompilation compilation, UUID grantId) {
        require(documentDigest.equals(compilation.documentDigest()), "OWNER_DOCUMENT_CHANGED");
        require(accessScopeDigest.equals(compilation.accessScopeDigest()), "OWNER_SCOPE_CHANGED");
        require(execution.grantId().equals(grantId), "EXECUTION_GRANT_CHANGED");
        var resources = new HashSet<String>();
        for (var pin : execution.resourceRevisions())
            require(resources.add(pin.kind() + ":" + pin.id()), "RESOURCE_PIN_DUPLICATE");
        require(
            execution
                .resourceRevisions()
                .stream()
                .filter(pin -> pin.kind() == ResourceKind.EXECUTION_GRANT)
                .count() == 1 &&
                resources.contains(ResourceKind.EXECUTION_GRANT + ":" + grantId),
            "GRANT_REVISION_REQUIRED"
        );
        var startPins = execution
            .resourceRevisions()
            .stream()
            .filter(pin -> pin.kind() == ResourceKind.RULE_START_CHECKPOINT)
            .toList();
        if (!startPins.isEmpty()) {
            var start = startPins.getFirst();
            var ruleId = new com.voltcanary.vcl.VclParser()
                .parse(compilation.canonicalDocument())
                .spec()
                .path("ruleId")
                .textValue();
            require(
                startPins.size() == 1 &&
                    start.revision() == 1 &&
                    start.id().toString().equals(ruleId) &&
                    start.snapshotDigest().equals(execution.snapshotSetDigest()),
                "BUNDLE_START_INPUTS_CHANGED"
            );
        }
        require(
            Wgs84ScalarKernel.VERSION.equals(execution.spatialKernelVersion()),
            "SPATIAL_KERNEL_UNAVAILABLE"
        );
        require(
            cost.stateBytes() <= cost.stateBudgetBytes() &&
                cost.workUnits() <= cost.workBudgetUnits(),
            "EXECUTION_BUDGET_EXCEEDED"
        );
        var found = new HashSet<VclCompilation.Dependency>();
        for (var check : dependencies) {
            require(found.add(check.dependency()), "OWNER_DEPENDENCY_DUPLICATE");
            require(check.authorized(), "OWNER_ACCESS_REVOKED");
            require(check.ready(), "OWNER_NOT_READY");
            require(check.indexReadiness() != IndexReadiness.MISSING, "INDEX_REQUIRED");
        }
        require(
            found.equals(new HashSet<>(compilation.dependencies())),
            "OWNER_DEPENDENCIES_CHANGED"
        );
        var methods = new HashSet<String>();
        var bindings = new HashSet<UUID>();
        for (var node : compilation.graph()) {
            methods.add(node.method());
            if (node.method().equals("emit_alert")) {
                require(
                    resources.contains(
                        ResourceKind.EVIDENCE_POLICY +
                            ":" +
                            node.config().path("evidencePolicyId").asText()
                    ),
                    "POLICY_REVISION_REQUIRED"
                );
            }
            spatialPins(node.config(), resources);
            if (node.method().equals("read_stream")) bindings.add(
                UUID.fromString(node.config().path("bindingId").asText())
            );
            if (node.method().equals("lookup_asof")) {
                var target = node.config().path("target");
                var id = UUID.fromString(target.path("datasetId").asText());
                long revision = target.path("schemaRevision").longValue();
                require(
                    dependencies
                        .stream()
                        .anyMatch(
                            check ->
                                check.dependency().id().equals(id) &&
                                check.dependency().kind() ==
                                    com.voltcanary.vcl.VclMetadata.Resource.DATASET &&
                                check.dependency().revision() == revision &&
                                check.indexReadiness() == IndexReadiness.READY
                        ),
                    "INDEX_REQUIRED"
                );
            }
        }
        require(methods.equals(execution.operatorVersions().keySet()), "OPERATOR_VERSIONS_CHANGED");
        require(
            bindings.equals(execution.bindingGenerations().keySet()),
            "INPUT_GENERATIONS_CHANGED"
        );
    }

    private static void spatialPins(
        com.fasterxml.jackson.databind.JsonNode node,
        java.util.Set<String> resources
    ) {
        if (
            node.path("op").asText().equals("CALL") && node.path("method").asText().equals("inside")
        ) {
            var reference = node.path("args").path(1);
            if (reference.path("op").asText().equals("CONST")) {
                require(
                    resources.contains(
                        ResourceKind.SPATIAL_FEATURE + ":" + reference.at("/literal/value").asText()
                    ),
                    "SPATIAL_REVISION_REQUIRED"
                );
            }
        }
        node.forEach(child -> spatialPins(child, resources));
    }

    private static void digest(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException(
            "Invalid manifest digest"
        );
    }

    private static void require(boolean condition, String code) {
        if (!condition) throw new VclException(code, "");
    }
}
