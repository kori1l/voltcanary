package com.voltcanary.analytics.vcl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclDocument;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclMetadata;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

class CompilationBundleValidationTest {

    @Test
    void explicitStartPinBindsCheckpointAcrossRestore() {
        var compilation = BundleFixtures.compilation("test");
        var validation = BundleFixtures.validation(compilation);
        var execution = validation.execution();
        var rule = UUID.fromString(
            BundleFixtures.document("test").spec().path("ruleId").textValue()
        );
        var resources = new ArrayList<>(execution.resourceRevisions());
        var start = new CompilationOwnerValidation.ResourcePin(
            CompilationOwnerValidation.ResourceKind.RULE_START_CHECKPOINT,
            rule,
            1,
            execution.snapshotSetDigest()
        );
        resources.add(start);
        var sealed = CompilationBundle.seal(
            UUID.randomUUID(),
            UUID.randomUUID(),
            compilation,
            withResources(validation, resources)
        );
        var restored = CompilationBundle.restore(
            sealed.accountId(),
            sealed.id(),
            sealed.payload(),
            sealed.digest()
        );
        restored.verifyStartCheckpoint(start.snapshotDigest());
        assertEquals(
            "BUNDLE_START_INPUTS_CHANGED",
            assertThrows(VclException.class, () ->
                restored.verifyStartCheckpoint("0".repeat(64))
            ).code()
        );
        resources.set(
            resources.size() - 1,
            new CompilationOwnerValidation.ResourcePin(start.kind(), start.id(), 1, "0".repeat(64))
        );
        assertEquals(
            "BUNDLE_START_INPUTS_CHANGED",
            assertThrows(VclException.class, () ->
                CompilationBundle.seal(
                    sealed.accountId(),
                    sealed.id(),
                    compilation,
                    withResources(validation, resources)
                )
            ).code()
        );
    }

    @Test
    void bundleRejectsRecompilationWithDifferentUnitRegistry() {
        var bundle = BundleFixtures.bundle(UUID.randomUUID(), UUID.randomUUID(), "test");
        var fresh = BundleFixtures.compilation("test");
        var changed = new com.voltcanary.vcl.VclCompilation(
            fresh.kind(),
            fresh.canonicalDocument(),
            fresh.documentDigest(),
            fresh.semanticDigest(),
            fresh.registryVersion(),
            fresh.compilerVersion(),
            fresh.registryDigest(),
            "0".repeat(64),
            fresh.accessScopeDigest(),
            fresh.dependencies(),
            fresh.graph()
        );
        assertEquals(
            "BUNDLE_COMPILER_PROFILE_CHANGED",
            assertThrows(VclException.class, () ->
                bundle.verifyCompiledDependencies(changed)
            ).code()
        );
    }

    @Test
    void restoredBundleRejectsDriftInAnOwnerDependencyDigest() {
        var original = BundleFixtures.bundle(UUID.randomUUID(), UUID.randomUUID(), "test");
        var bundle = CompilationBundle.restore(
            original.accountId(),
            original.id(),
            original.payload(),
            original.digest()
        );
        var fresh = BundleFixtures.compilation("test");
        bundle.verifyCompiledDependencies(fresh);
        var dependencies = new ArrayList<>(fresh.dependencies());
        var old = dependencies.getFirst();
        dependencies.set(
            0,
            new com.voltcanary.vcl.VclCompilation.Dependency(
                old.kind(),
                old.id(),
                old.revision(),
                "0".repeat(64)
            )
        );
        var changed = new com.voltcanary.vcl.VclCompilation(
            fresh.kind(),
            fresh.canonicalDocument(),
            fresh.documentDigest(),
            fresh.semanticDigest(),
            fresh.registryVersion(),
            fresh.compilerVersion(),
            fresh.registryDigest(),
            fresh.unitRegistryDigest(),
            fresh.accessScopeDigest(),
            dependencies,
            fresh.graph()
        );
        assertEquals(
            "BUNDLE_DEPENDENCIES_CHANGED",
            assertThrows(VclException.class, () ->
                bundle.verifyCompiledDependencies(changed)
            ).code()
        );
    }

    @Test
    void bundleRequiresTheExactReviewedGrantAndPolicyPins() {
        var bundle = BundleFixtures.bundle(UUID.randomUUID(), UUID.randomUUID(), "test");
        var pins = bundle.ownerValidation().execution().resourceRevisions();
        var grant = pins
            .stream()
            .filter(pin -> pin.kind() == CompilationOwnerValidation.ResourceKind.EXECUTION_GRANT)
            .findFirst()
            .orElseThrow();
        var policy = pins
            .stream()
            .filter(pin -> pin.kind() == CompilationOwnerValidation.ResourceKind.EVIDENCE_POLICY)
            .findFirst()
            .orElseThrow();
        bundle.verifyReviewedOwnerPins(grant, policy);
        var changed = new CompilationOwnerValidation.ResourcePin(
            policy.kind(),
            policy.id(),
            policy.revision(),
            "0".repeat(64)
        );
        assertEquals(
            "BUNDLE_OWNER_PINS_CHANGED",
            assertThrows(VclException.class, () ->
                bundle.verifyReviewedOwnerPins(grant, changed)
            ).code()
        );
    }

    @Test
    void restoredBundleRequiresOriginalStartBoundaryAndGenerations() {
        var original = BundleFixtures.bundle(UUID.randomUUID(), UUID.randomUUID(), "test");
        var bundle = CompilationBundle.restore(
            original.accountId(),
            original.id(),
            original.payload(),
            original.digest()
        );
        var pins = bundle.ownerValidation().execution();
        var boundary = java.time.Instant.parse(pins.startBoundary());
        bundle.verifyStartInputs(boundary, pins.bindingGenerations());
        assertEquals(
            "BUNDLE_START_INPUTS_CHANGED",
            assertThrows(VclException.class, () ->
                bundle.verifyStartInputs(boundary.plusSeconds(1), pins.bindingGenerations())
            ).code()
        );
        var binding = pins.bindingGenerations().keySet().iterator().next();
        assertEquals(
            "BUNDLE_START_INPUTS_CHANGED",
            assertThrows(VclException.class, () ->
                bundle.verifyStartInputs(boundary, Map.of(binding, UUID.randomUUID()))
            ).code()
        );
    }

    @Test
    void sealedBundleIsStableAndRoundTripsItsPinnedManifest() {
        var compilation = BundleFixtures.compilation("private-label");
        var validation = BundleFixtures.validation(compilation);
        var account = UUID.randomUUID();
        var id = UUID.randomUUID();
        var a = CompilationBundle.seal(account, id, compilation, validation);
        var b = CompilationBundle.seal(account, id, compilation, validation);
        assertEquals(a.payload(), b.payload());
        assertEquals(validation, a.ownerValidation());
        ((ObjectNode) a.json()).remove("ownerValidation");
        assertEquals(
            validation.documentDigest(),
            a.json().at("/ownerValidation/documentDigest").textValue()
        );
        assertEquals(
            a.payload(),
            CompilationBundle.restore(account, id, a.payload(), a.digest()).payload()
        );
        assertFalse(a.toString().contains("private-label"));
        assertEquals(
            "BUNDLE_INTEGRITY",
            assertThrows(VclException.class, () ->
                CompilationBundle.restore(UUID.randomUUID(), id, a.payload(), a.digest())
            ).code()
        );
    }

    @Test
    void canonicalHashAloneCannotAuthorizeUnknownEnvelopeFields() throws Exception {
        var account = UUID.randomUUID();
        var id = UUID.randomUUID();
        var bundle = BundleFixtures.bundle(account, id, "private-label");
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var changed = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(
            bundle.payload()
        );
        changed.remove("ownerValidation");
        changed.put("sql", "private-label");
        String payload = com.voltcanary.vcl.VclCanonical.encode(changed);
        var error = assertThrows(VclException.class, () ->
            CompilationBundle.restore(
                account,
                id,
                payload,
                com.voltcanary.vcl.VclCanonical.sha256(payload)
            )
        );
        assertEquals("BUNDLE_INTEGRITY", error.code());
        assertFalse(error.getMessage().contains("private-label"));
        String noncanonical = bundle.payload() + " ";
        assertEquals(
            "BUNDLE_INTEGRITY",
            assertThrows(VclException.class, () ->
                CompilationBundle.restore(
                    account,
                    id,
                    noncanonical,
                    com.voltcanary.vcl.VclCanonical.sha256(noncanonical)
                )
            ).code()
        );
    }

    @Test
    void restoreRevalidatesPinnedManifestEvenWithRecomputedHash() throws Exception {
        var account = UUID.randomUUID();
        var id = UUID.randomUUID();
        var bundle = BundleFixtures.bundle(account, id, "test");
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        for (String field : List.of("authorized", "ready")) {
            var changed = mapper.readTree(bundle.payload());
            (
                (com.fasterxml.jackson.databind.node.ObjectNode) changed.at(
                    "/ownerValidation/dependencies/0"
                )
            ).put(field, false);
            String payload = com.voltcanary.vcl.VclCanonical.encode(changed);
            assertEquals(
                "BUNDLE_INTEGRITY",
                assertThrows(VclException.class, () ->
                    CompilationBundle.restore(
                        account,
                        id,
                        payload,
                        com.voltcanary.vcl.VclCanonical.sha256(payload)
                    )
                ).code()
            );
        }
    }

    @Test
    void restoreRejectsGraphDriftEvenWithRecomputedHash() throws Exception {
        var account = UUID.randomUUID();
        var id = UUID.randomUUID();
        var bundle = BundleFixtures.bundle(account, id, "test");
        var mapper = new ObjectMapper();
        for (String change : List.of("config", "edge", "order", "missing", "duplicate")) {
            var changed = mapper.readTree(bundle.payload());
            var graph = (ArrayNode) changed.at("/compilation/graph");
            var terminal = (ObjectNode) graph.get(graph.size() - 1);
            switch (change) {
                case "config" -> ((ObjectNode) terminal.path("config")).put("severity", "CRITICAL");
                case "edge" -> ((ObjectNode) terminal.path("inputs")).put(
                    "decisions",
                    graph.get(0).path("id").textValue()
                );
                case "order" -> graph.insert(0, graph.remove(graph.size() - 1));
                case "missing" -> graph.remove(0);
                case "duplicate" -> graph.insert(0, graph.get(0).deepCopy());
                default -> throw new AssertionError(change);
            }
            String payload = VclCanonical.encode(changed);
            assertFalse(bundle.payload().equals(payload), change);
            assertEquals(
                "BUNDLE_INTEGRITY",
                assertThrows(
                    VclException.class,
                    () ->
                        CompilationBundle.restore(
                            account,
                            id,
                            payload,
                            VclCanonical.sha256(payload)
                        ),
                    change
                ).code()
            );
        }
    }

    @Test
    void restoreRejectsMatchingDependencyAndOwnerPinsThatDisagreeWithRule() throws Exception {
        var account = UUID.randomUUID();
        var id = UUID.randomUUID();
        var bundle = BundleFixtures.bundle(account, id, "test");
        var mapper = new ObjectMapper();
        for (String field : List.of("id", "revision", "snapshotDigest")) {
            var changed = mapper.readTree(bundle.payload());
            for (String path : List.of(
                "/compilation/dependencies/0",
                "/ownerValidation/dependencies/0/dependency"
            )) {
                var pin = (ObjectNode) changed.at(path);
                switch (field) {
                    case "id" -> pin.put(field, "00000000-0000-4000-8000-000000000001");
                    case "revision" -> pin.put(field, pin.path(field).longValue() + 1);
                    case "snapshotDigest" -> pin.put(field, "invalid");
                    default -> throw new AssertionError(field);
                }
            }
            String payload = VclCanonical.encode(changed);
            assertEquals(
                "BUNDLE_INTEGRITY",
                assertThrows(
                    VclException.class,
                    () ->
                        CompilationBundle.restore(
                            account,
                            id,
                            payload,
                            VclCanonical.sha256(payload)
                        ),
                    field
                ).code()
            );
        }
    }

    @TestFactory
    List<DynamicTest> ownerReadinessCannotBeAssertedByGrammarAlone() {
        var compilation = BundleFixtures.compilation("test");
        var good = BundleFixtures.validation(compilation);
        var tests = new ArrayList<DynamicTest>();
        tests.add(
            reject(
                "OWNER_DOCUMENT_CHANGED",
                compilation,
                new CompilationOwnerValidation(
                    "f".repeat(64),
                    good.accessScopeDigest(),
                    good.dependencies(),
                    good.execution(),
                    good.cost()
                )
            )
        );
        tests.add(
            reject(
                "OWNER_SCOPE_CHANGED",
                compilation,
                new CompilationOwnerValidation(
                    good.documentDigest(),
                    "f".repeat(64),
                    good.dependencies(),
                    good.execution(),
                    good.cost()
                )
            )
        );
        tests.add(reject("OWNER_DEPENDENCIES_CHANGED", compilation, withChecks(good, List.of())));
        var duplicates = new ArrayList<>(good.dependencies());
        duplicates.add(duplicates.getFirst());
        tests.add(reject("OWNER_DEPENDENCY_DUPLICATE", compilation, withChecks(good, duplicates)));
        for (String code : List.of("OWNER_ACCESS_REVOKED", "OWNER_NOT_READY", "INDEX_REQUIRED")) {
            var checks = good
                .dependencies()
                .stream()
                .map(check ->
                    new CompilationOwnerValidation.DependencyCheck(
                        check.dependency(),
                        !code.equals("OWNER_ACCESS_REVOKED"),
                        !code.equals("OWNER_NOT_READY"),
                        code.equals("INDEX_REQUIRED")
                            ? CompilationOwnerValidation.IndexReadiness.NOT_REQUIRED
                            : check.indexReadiness(),
                        check.capabilityDigest()
                    )
                )
                .toList();
            tests.add(reject(code, compilation, withChecks(good, checks)));
        }
        tests.add(
            reject(
                "EXECUTION_BUDGET_EXCEEDED",
                compilation,
                new CompilationOwnerValidation(
                    good.documentDigest(),
                    good.accessScopeDigest(),
                    good.dependencies(),
                    good.execution(),
                    new CompilationOwnerValidation.Cost(4097, 4096, 10, 100)
                )
            )
        );
        tests.add(
            reject(
                "EXECUTION_BUDGET_EXCEEDED",
                compilation,
                new CompilationOwnerValidation(
                    good.documentDigest(),
                    good.accessScopeDigest(),
                    good.dependencies(),
                    good.execution(),
                    new CompilationOwnerValidation.Cost(1024, 4096, 101, 100)
                )
            )
        );
        var pins = good.execution();
        tests.add(
            reject(
                "EXECUTION_GRANT_CHANGED",
                compilation,
                withPins(
                    good,
                    new CompilationOwnerValidation.ExecutionPins(
                        UUID.randomUUID(),
                        pins.bindingGenerations(),
                        pins.startBoundary(),
                        pins.keyDefinitionDigest(),
                        pins.snapshotSetDigest(),
                        pins.resourceRevisions(),
                        pins.spatialKernelVersion(),
                        pins.operatorVersions()
                    )
                )
            )
        );
        tests.add(
            reject(
                "SPATIAL_KERNEL_UNAVAILABLE",
                compilation,
                withPins(
                    good,
                    new CompilationOwnerValidation.ExecutionPins(
                        pins.grantId(),
                        pins.bindingGenerations(),
                        pins.startBoundary(),
                        pins.keyDefinitionDigest(),
                        pins.snapshotSetDigest(),
                        pins.resourceRevisions(),
                        "missing-kernel",
                        pins.operatorVersions()
                    )
                )
            )
        );
        tests.add(
            reject(
                "OPERATOR_VERSIONS_CHANGED",
                compilation,
                withPins(
                    good,
                    new CompilationOwnerValidation.ExecutionPins(
                        pins.grantId(),
                        pins.bindingGenerations(),
                        pins.startBoundary(),
                        pins.keyDefinitionDigest(),
                        pins.snapshotSetDigest(),
                        pins.resourceRevisions(),
                        pins.spatialKernelVersion(),
                        Map.of("read_stream", "fixture-v1")
                    )
                )
            )
        );
        tests.add(
            reject(
                "INPUT_GENERATIONS_CHANGED",
                compilation,
                withPins(
                    good,
                    new CompilationOwnerValidation.ExecutionPins(
                        pins.grantId(),
                        Map.of(UUID.randomUUID(), UUID.randomUUID()),
                        pins.startBoundary(),
                        pins.keyDefinitionDigest(),
                        pins.snapshotSetDigest(),
                        pins.resourceRevisions(),
                        pins.spatialKernelVersion(),
                        pins.operatorVersions()
                    )
                )
            )
        );
        tests.add(
            reject(
                "GRANT_REVISION_REQUIRED",
                compilation,
                withResources(
                    good,
                    pins
                        .resourceRevisions()
                        .stream()
                        .filter(
                            pin ->
                                pin.kind() !=
                                CompilationOwnerValidation.ResourceKind.EXECUTION_GRANT
                        )
                        .toList()
                )
            )
        );
        tests.add(
            reject(
                "POLICY_REVISION_REQUIRED",
                compilation,
                withResources(
                    good,
                    pins
                        .resourceRevisions()
                        .stream()
                        .filter(
                            pin ->
                                pin.kind() !=
                                CompilationOwnerValidation.ResourceKind.EVIDENCE_POLICY
                        )
                        .toList()
                )
            )
        );
        var repeatedPins = new ArrayList<>(pins.resourceRevisions());
        repeatedPins.add(repeatedPins.getFirst());
        tests.add(reject("RESOURCE_PIN_DUPLICATE", compilation, withResources(good, repeatedPins)));
        return tests;
    }

    @Test
    void asOfIndexReadinessMustMatchTheExactSchemaRevision() {
        var original = BundleFixtures.compilation("test");
        var dataset = original
            .dependencies()
            .stream()
            .filter(dependency -> dependency.kind() == VclMetadata.Resource.DATASET)
            .findFirst()
            .orElseThrow();
        var otherRevision = new com.voltcanary.vcl.VclCompilation.Dependency(
            dataset.kind(),
            dataset.id(),
            dataset.revision() + 1,
            dataset.snapshotDigest()
        );
        var dependencies = new ArrayList<>(original.dependencies());
        dependencies.add(otherRevision);
        var compilation = new com.voltcanary.vcl.VclCompilation(
            original.kind(),
            original.canonicalDocument(),
            original.documentDigest(),
            original.semanticDigest(),
            original.registryVersion(),
            original.compilerVersion(),
            original.registryDigest(),
            original.unitRegistryDigest(),
            original.accessScopeDigest(),
            dependencies,
            original.graph()
        );
        var ready = BundleFixtures.validation(compilation);
        ready.verify(compilation, BundleFixtures.GRANT);
        var checks = ready
            .dependencies()
            .stream()
            .map(check ->
                check.dependency().equals(dataset)
                    ? new CompilationOwnerValidation.DependencyCheck(
                          dataset,
                          true,
                          true,
                          CompilationOwnerValidation.IndexReadiness.NOT_REQUIRED,
                          check.capabilityDigest()
                      )
                    : check
            )
            .toList();
        assertEquals(
            "INDEX_REQUIRED",
            assertThrows(VclException.class, () ->
                withChecks(ready, checks).verify(compilation, BundleFixtures.GRANT)
            ).code()
        );
    }

    @Test
    void prepareAndInspectionReauthorizeBeforeReturningPrivateBundle() {
        var writes = new AtomicInteger();
        var snapshots = new AtomicInteger();
        var deny = new java.util.concurrent.atomic.AtomicBoolean(true);
        var saved = new java.util.concurrent.atomic.AtomicReference<CompilationBundle>();
        var store = new CompilationBundleStore() {
            public CompilationBundle put(CompilationBundle bundle) {
                writes.incrementAndGet();
                saved.set(bundle);
                deny.set(true);
                return bundle;
            }

            public Optional<CompilationBundle> find(UUID accountId, UUID bundleId) {
                return Optional.ofNullable(saved.get()).filter(bundle ->
                    bundle.accountId().equals(accountId)
                );
            }
        };
        var owners = new CompilationBundlePreparation.Owners() {
            public VclMetadata snapshot(UUID accountId, VclDocument document) {
                snapshots.incrementAndGet();
                return BundleFixtures.METADATA;
            }

            public CompilationOwnerValidation validate(
                UUID accountId,
                com.voltcanary.vcl.VclCompilation compilation
            ) {
                return BundleFixtures.validation(compilation);
            }

            public void reauthorize(UUID accountId, CompilationOwnerValidation validation) {
                if (deny.get()) throw new VclException("OWNER_ACCESS_REVOKED", "");
            }
        };
        var service = new CompilationBundlePreparation(BundleFixtures.COMPILER, owners, store);
        var account = UUID.randomUUID();
        var id = UUID.randomUUID();
        assertEquals(
            "OWNER_ACCESS_REVOKED",
            assertThrows(VclException.class, () ->
                service.prepare(account, id, BundleFixtures.document("test"))
            ).code()
        );
        assertEquals(0, writes.get());
        deny.set(false);
        assertEquals(
            "OWNER_ACCESS_REVOKED",
            assertThrows(VclException.class, () ->
                service.prepare(account, id, BundleFixtures.document("test"))
            ).code()
        );
        assertEquals(1, writes.get());
        assertEquals(
            "OWNER_ACCESS_REVOKED",
            assertThrows(VclException.class, () -> service.inspect(account, id)).code()
        );
        deny.set(false);
        var bundle = service.prepare(account, id, BundleFixtures.document("test"));
        assertEquals(saved.get().digest(), bundle.digest());
        assertEquals(bundle.digest(), service.inspect(account, id).digest());
        assertEquals(
            bundle.digest(),
            service.prepare(account, id, BundleFixtures.document("test")).digest()
        );
        assertEquals(2, snapshots.get());
        assertEquals(1, writes.get());
        assertEquals(
            "BUNDLE_ID_REUSED",
            assertThrows(VclException.class, () ->
                service.prepare(account, id, BundleFixtures.document("changed"))
            ).code()
        );
        deny.set(true);
        assertEquals(
            "OWNER_ACCESS_REVOKED",
            assertThrows(VclException.class, () ->
                service.prepare(account, id, BundleFixtures.document("test"))
            ).code()
        );
        assertEquals(
            "OWNER_ACCESS_REVOKED",
            assertThrows(VclException.class, () -> service.inspect(account, id)).code()
        );
        assertEquals(
            "BUNDLE_UNAVAILABLE",
            assertThrows(VclException.class, () -> service.inspect(UUID.randomUUID(), id)).code()
        );
    }

    private static CompilationOwnerValidation withChecks(
        CompilationOwnerValidation validation,
        List<CompilationOwnerValidation.DependencyCheck> checks
    ) {
        return new CompilationOwnerValidation(
            validation.documentDigest(),
            validation.accessScopeDigest(),
            checks,
            validation.execution(),
            validation.cost()
        );
    }

    private static CompilationOwnerValidation withPins(
        CompilationOwnerValidation validation,
        CompilationOwnerValidation.ExecutionPins pins
    ) {
        return new CompilationOwnerValidation(
            validation.documentDigest(),
            validation.accessScopeDigest(),
            validation.dependencies(),
            pins,
            validation.cost()
        );
    }

    private static CompilationOwnerValidation withResources(
        CompilationOwnerValidation validation,
        List<CompilationOwnerValidation.ResourcePin> resources
    ) {
        var pins = validation.execution();
        return withPins(
            validation,
            new CompilationOwnerValidation.ExecutionPins(
                pins.grantId(),
                pins.bindingGenerations(),
                pins.startBoundary(),
                pins.keyDefinitionDigest(),
                pins.snapshotSetDigest(),
                resources,
                pins.spatialKernelVersion(),
                pins.operatorVersions()
            )
        );
    }

    private static DynamicTest reject(
        String code,
        com.voltcanary.vcl.VclCompilation compilation,
        CompilationOwnerValidation validation
    ) {
        return DynamicTest.dynamicTest(code, () ->
            assertEquals(
                code,
                assertThrows(VclException.class, () ->
                    CompilationBundle.seal(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        compilation,
                        validation
                    )
                ).code()
            )
        );
    }
}
