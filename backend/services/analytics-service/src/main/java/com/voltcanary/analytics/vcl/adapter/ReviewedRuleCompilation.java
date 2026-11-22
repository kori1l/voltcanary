package com.voltcanary.analytics.vcl.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.voltcanary.analytics.vcl.CompilationBundle;
import com.voltcanary.analytics.vcl.KeyedStateStore;
import com.voltcanary.analytics.vcl.ScalarValue;
import com.voltcanary.analytics.vcl.VclWindowPlan;
import com.voltcanary.analytics.vcl.VclWindowSessionKernel;
import com.voltcanary.analytics.vcl.adapter.ReviewedRuleCutovers.CompilationReceipt;
import com.voltcanary.analytics.vcl.adapter.ReviewedRuleCutovers.Lookup;
import com.voltcanary.analytics.vcl.adapter.ReviewedRuleCutovers.Preview;
import com.voltcanary.analytics.vcl.adapter.ReviewedRuleCutovers.SessionPreview;
import com.voltcanary.analytics.vcl.adapter.ReviewedRuleCutovers.SessionPreviewRequest;
import com.voltcanary.common.web.ConfigurableOwnerReads;
import com.voltcanary.vcl.VclCompiler;
import com.voltcanary.vcl.VclDocument;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclParser;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Prepares and verifies owner-bound compilation checkpoints. */
final class ReviewedRuleCompilation {

    private final JdbcCompilationBundleStore bundles;
    private final NativeDatasetDependencies dependencies;
    private final RuleCompilationSnapshots snapshots;
    private final ConfigurableOwnerReads device;
    private final VclParser parser;
    private final VclCompiler compiler;

    ReviewedRuleCompilation(
        JdbcCompilationBundleStore bundles,
        NativeDatasetDependencies dependencies,
        RuleCompilationSnapshots snapshots,
        ConfigurableOwnerReads device,
        VclParser parser,
        VclCompiler compiler
    ) {
        this.bundles = bundles;
        this.dependencies = dependencies;
        this.snapshots = snapshots;
        this.device = device;
        this.parser = parser;
        this.compiler = compiler;
    }

    JsonNode circleDependencies(String authorization, UUID actor, UUID id, long revision) {
        var owned = ownedCircle(authorization, id, revision);
        return dependencies.inspectCircle(owned.accountId(), owned.id(), owned.revision());
    }

    RuleCompilationSnapshots.StartCheckpoint preview(
        String authorization,
        UUID actor,
        Preview preview
    ) {
        var bundle = bundle(preview.account(), preview.bundle());
        var checkpoint = snapshots.prepareStartCheckpoint(
            authorization,
            actor,
            preview.account(),
            document(bundle),
            compiler,
            Long.MAX_VALUE,
            preview.from(),
            preview.boundary(),
            preview.proofs(),
            selections(preview.lookups()),
            bundle
        );
        verifyBundleCircles(authorization, bundle);
        return checkpoint;
    }

    SessionPreview previewSession(String authorization, UUID actor, SessionPreviewRequest request) {
        if (
            request == null ||
            request.account() == null ||
            request.bundle() == null ||
            request.checkpoint() == null ||
            request.node() == null ||
            request.subjectLiteral() == null ||
            request.occurrenceLiteral() == null ||
            request.from() == null ||
            request.until() == null ||
            !request.from().isBefore(request.until()) ||
            request.from().getNano() % 1000 != 0 ||
            request.until().getNano() % 1000 != 0 ||
            Duration.between(request.from(), request.until()).compareTo(Duration.ofDays(32)) > 0
        ) {
            throw new VclException("SESSION_PREVIEW_INVALID", "");
        }
        var bundle = bundle(request.account(), request.bundle());
        var document = document(bundle);
        var snapshot = snapshots.verifiedStartSnapshot(
            authorization,
            actor,
            request.account(),
            document,
            compiler,
            Long.MAX_VALUE,
            request.checkpoint(),
            bundle
        );
        snapshot.verifyBundleOwners(bundle);
        var geometry = previewGeometry(authorization, bundle, snapshot, request.circle());
        var plan = snapshot.prepareSessionAbsence(
            compiler,
            request.node(),
            null,
            geometry,
            request.circle()
        );
        if ((plan.zoneReplay() != null) != (geometry != null)) throw new VclException(
            "SESSION_PREVIEW_INVALID",
            ""
        );
        var subject = com.voltcanary.analytics.vcl.ScalarValue.known(
            parser,
            request.subjectLiteral()
        );
        var occurrence = com.voltcanary.analytics.vcl.ScalarValue.known(
            parser,
            request.occurrenceLiteral()
        );
        var key = new com.voltcanary.analytics.vcl.KeyedStateStore.Key(
            request.account(),
            UUID.fromString(document.spec().path("ruleId").textValue()),
            bundle.id(),
            VclWindowSessionKernel.identity(subject),
            VclWindowSessionKernel.identity(occurrence)
        );
        var window = new com.voltcanary.analytics.vcl.VclWindowPlan.Window(
            request.from(),
            request.until()
        );
        var facts = snapshot.sessionPreviewFacts(plan, key, window, request.proof());
        var result = new com.voltcanary.analytics.vcl.VclSessionAbsenceKernel().preview(
            plan,
            key,
            facts.cut(),
            facts.proof(),
            plan.scope()
        );
        snapshot.verifyBundleOwners(bundle);
        if (
            geometry != null &&
            !geometry
                .digest()
                .equals(previewGeometry(authorization, bundle, snapshot, request.circle()).digest())
        ) {
            throw new VclException("SPATIAL_REVISION_CHANGED", "");
        }
        if (
            !java.util.Objects.equals(
                facts.proof(),
                snapshot.sessionPreviewProof(plan, window, request.proof())
            )
        ) {
            throw new VclException("SESSION_COVERAGE_CHANGED", "");
        }
        var verdict = result.absence();
        var witness = result.witness();
        var proof = facts.proof();
        return new SessionPreview(
            facts.cut().throughCheckpoint(),
            verdict.status().name(),
            verdict.reason(),
            verdict.literal(),
            result.window(),
            result.entry(),
            result.exit(),
            witness == null ? null : witness.firstObservationId(),
            witness == null ? null : witness.lastObservationId(),
            result.positionWitnesses(),
            verdict.evidence(),
            proof != null && proof.qualifiesEventTime() ? proof.eventTimeDigest() : null
        );
    }

    com.voltcanary.analytics.vcl.SpatialFeatureSnapshot previewGeometry(
        String authorization,
        CompilationBundle bundle,
        RuleCompilationSnapshot snapshot,
        UUID id
    ) {
        if (id == null) return null;
        for (var pin : bundle.json().at("/ownerValidation/execution/resourceRevisions")) {
            if (
                !"SPATIAL_FEATURE".equals(pin.path("kind").textValue()) ||
                !id.toString().equals(pin.path("id").textValue())
            ) continue;
            var revision = circle(
                authorization,
                bundle.accountId(),
                id,
                pin.path("revision").longValue()
            );
            if (!revision.digest().equals(pin.path("snapshotDigest").textValue())) break;
            var shape = revision.geometry();
            var geometry = new com.voltcanary.analytics.vcl.SpatialFeatureSnapshot.Circle(
                id,
                revision.revision(),
                new com.voltcanary.analytics.vcl.SpatialFeatureSnapshot.Point(
                    shape.longitude(),
                    shape.latitude()
                ),
                new java.math.BigDecimal(shape.radiusMetres())
            );
            return new com.voltcanary.analytics.vcl.SpatialFeatureSnapshot(
                snapshot.sessionScope(),
                Map.of(id, geometry)
            );
        }
        throw new VclException("SPATIAL_REVISION_CHANGED", "");
    }

    CompilationReceipt compile(String authorization, UUID actor, byte[] body) {
        var request = parser.readJson(body);
        var readSet = request.path("readSet");
        if (
            !request.isObject() ||
            request.size() != 2 ||
            !request.path("document").isObject() ||
            !readSet.isObject() ||
            readSet.size() !=
                6 + (readSet.has("lookups") ? 1 : 0) + (readSet.has("circles") ? 1 : 0) ||
            !readSet.path("schemaRevision").isIntegralNumber() ||
            !readSet.path("schemaRevision").canConvertToLong()
        ) {
            throw new VclException("RULE_COMPILATION_REQUEST_INVALID", "");
        }
        UUID account;
        Instant from;
        Instant boundary;
        RuleCompilationSnapshot.SourceStartProof proof;
        try {
            account = UUID.fromString(readSet.path("account").textValue());
            from = Instant.parse(readSet.path("from").textValue());
            boundary = Instant.parse(readSet.path("boundary").textValue());
            proof = new RuleCompilationSnapshot.SourceStartProof(
                UUID.fromString(readSet.path("binding").textValue()),
                readSet.path("schemaRevision").longValue(),
                UUID.fromString(readSet.path("proofId").textValue())
            );
        } catch (
            IllegalArgumentException
            | NullPointerException
            | java.time.format.DateTimeParseException invalid
        ) {
            throw new VclException("RULE_COMPILATION_REQUEST_INVALID", "");
        }
        var document = parser.parse(request.path("document").toString());
        var lookups = compilationLookups(readSet);
        var snapshot = snapshots.captureJournal(
            authorization,
            actor,
            account,
            document,
            com.voltcanary.analytics.vcl.VclDurationWitnessKernel.MAX_INPUTS
        );
        var prepared = snapshots.prepareCompilationStart(
            snapshot,
            compiler,
            authorization,
            from,
            boundary,
            List.of(proof),
            lookups
        );
        var circles = compilationCircles(authorization, account, readSet);
        var validation = snapshot.bundleValidation(compiler, prepared, circles);
        var canonical = CompilationBundle.seal(
            account,
            new UUID(0, 0),
            prepared.compilation(),
            validation
        );
        UUID bundleId = UUID.nameUUIDFromBytes(
            ("RULE_COMPILATION_ID/1\n" + canonical.digest()).getBytes(StandardCharsets.UTF_8)
        );
        var bundle = CompilationBundle.seal(account, bundleId, prepared.compilation(), validation);
        snapshot.verifyCompilationStart(prepared);
        verifyCompilationCircles(authorization, account, circles);
        var stored = bundles.put(bundle);
        snapshot.verifyCompilationStart(prepared);
        verifyCompilationCircles(authorization, account, circles);
        return new CompilationReceipt(stored.id(), stored.digest(), prepared.checkpoint());
    }

    List<com.voltcanary.vcl.VclCircleRevision> compilationCircles(
        String authorization,
        UUID account,
        JsonNode readSet
    ) {
        if (!readSet.has("circles")) return List.of();
        var entries = readSet.path("circles");
        if (!entries.isArray() || entries.isEmpty() || entries.size() > 16) {
            throw new VclException("RULE_COMPILATION_REQUEST_INVALID", "");
        }
        var ids = new java.util.HashSet<UUID>();
        var circles = new java.util.ArrayList<com.voltcanary.vcl.VclCircleRevision>();
        for (var entry : entries) {
            if (
                !entry.isObject() ||
                entry.size() != 2 ||
                !entry.path("id").isTextual() ||
                !entry.path("revision").isIntegralNumber() ||
                !entry.path("revision").canConvertToLong() ||
                entry.path("revision").longValue() < 1
            ) {
                throw new VclException("RULE_COMPILATION_REQUEST_INVALID", "");
            }
            UUID id;
            try {
                id = UUID.fromString(entry.path("id").textValue());
            } catch (IllegalArgumentException invalid) {
                throw new VclException("RULE_COMPILATION_REQUEST_INVALID", "");
            }
            if (!ids.add(id)) throw new VclException("RULE_COMPILATION_REQUEST_INVALID", "");
            circles.add(circle(authorization, account, id, entry.path("revision").longValue()));
        }
        return List.copyOf(circles);
    }

    void verifyCompilationCircles(
        String authorization,
        UUID account,
        List<com.voltcanary.vcl.VclCircleRevision> circles
    ) {
        for (var expected : circles) {
            if (
                !expected.equals(circle(authorization, account, expected.id(), expected.revision()))
            ) {
                throw new VclException("SPATIAL_REVISION_CHANGED", "");
            }
        }
    }

    void verifyBundleCircles(String authorization, CompilationBundle bundle) {
        for (var pin : bundle.json().at("/ownerValidation/execution/resourceRevisions")) {
            if (!"SPATIAL_FEATURE".equals(pin.path("kind").textValue())) continue;
            var current = circle(
                authorization,
                bundle.accountId(),
                UUID.fromString(pin.path("id").textValue()),
                pin.path("revision").longValue()
            );
            if (!current.digest().equals(pin.path("snapshotDigest").textValue())) {
                throw new VclException("SPATIAL_REVISION_CHANGED", "");
            }
        }
    }

    com.voltcanary.vcl.VclCircleRevision circle(
        String authorization,
        UUID account,
        UUID id,
        long revision
    ) {
        var circle = ownedCircle(authorization, id, revision);
        if (!account.equals(circle.accountId())) throw new VclException(
            "SPATIAL_REVISION_CHANGED",
            ""
        );
        return circle;
    }

    com.voltcanary.vcl.VclCircleRevision ownedCircle(String authorization, UUID id, long revision) {
        try {
            var circle = com.voltcanary.vcl.VclCircleRevision.parse(
                device.circleRevision(
                    authorization,
                    id,
                    revision,
                    System.nanoTime() + Duration.ofSeconds(20).toNanos()
                )
            );
            if (!id.equals(circle.id()) || revision != circle.revision()) {
                throw new VclException("SPATIAL_REVISION_CHANGED", "");
            }
            return circle;
        } catch (ConfigurableOwnerReads.Failure failure) {
            throw new VclException(failure.code(), "");
        }
    }

    Map<String, RuleCompilationSnapshot.LookupSelection> compilationLookups(JsonNode readSet) {
        if (!readSet.has("lookups")) return Map.of();
        var supplied = readSet.path("lookups");
        if (!supplied.isObject() || supplied.isEmpty() || supplied.size() > 2) {
            throw new VclException("RULE_COMPILATION_REQUEST_INVALID", "");
        }
        var result = new LinkedHashMap<String, RuleCompilationSnapshot.LookupSelection>();
        supplied.fields().forEachRemaining(entry -> {
            var lookup = entry.getValue();
            if (
                !lookup.isObject() ||
                lookup.size() != 2 ||
                !lookup.path("query").isObject() ||
                !lookup.path("intervalField").isTextual()
            ) {
                throw new VclException("RULE_COMPILATION_REQUEST_INVALID", "");
            }
            UUID interval;
            try {
                interval = UUID.fromString(lookup.path("intervalField").textValue());
            } catch (IllegalArgumentException invalid) {
                throw new VclException("RULE_COMPILATION_REQUEST_INVALID", "");
            }
            result.put(
                entry.getKey(),
                new RuleCompilationSnapshot.LookupSelection(
                    parser.parse(lookup.path("query").toString()),
                    interval
                )
            );
        });
        return result;
    }

    Map<String, RuleCompilationSnapshot.LookupSelection> selections(Map<String, Lookup> lookups) {
        var selections = new LinkedHashMap<String, RuleCompilationSnapshot.LookupSelection>();
        if (lookups != null) lookups.forEach((node, lookup) -> {
            if (lookup == null || lookup.query() == null || lookup.intervalField() == null) {
                throw new VclException("ASOF_PROJECTION_CONFLICT", "");
            }
            selections.put(
                node,
                new RuleCompilationSnapshot.LookupSelection(
                    parser.parse(lookup.query()),
                    lookup.intervalField()
                )
            );
        });
        return selections;
    }

    CompilationBundle bundle(UUID account, UUID id) {
        return bundles
            .find(account, id)
            .orElseThrow(() -> new VclException("DEPLOYMENT_BUNDLE_UNAVAILABLE", ""));
    }

    com.fasterxml.jackson.databind.JsonNode compilation(CompilationBundle bundle) {
        return bundle.json().path("compilation");
    }

    VclDocument document(CompilationBundle bundle) {
        return parser.parse(compilation(bundle).path("canonicalDocument").textValue());
    }
}
