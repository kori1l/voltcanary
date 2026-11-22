package com.voltcanary.analytics.vcl.adapter;

import com.voltcanary.analytics.vcl.CompilationOwnerValidation.ResourcePin;
import com.voltcanary.analytics.vcl.DurableSustained;
import com.voltcanary.analytics.vcl.KeyedStateStore;
import com.voltcanary.analytics.vcl.SpatialFeatureSnapshot;
import com.voltcanary.analytics.vcl.VclDurationWitnessKernel;
import com.voltcanary.analytics.vcl.VclIntervalUnionKernel;
import com.voltcanary.analytics.vcl.VclIntervalUnionPlan;
import com.voltcanary.analytics.vcl.VclWindowAggregateKernel;
import com.voltcanary.analytics.vcl.adapter.RuleCompilationSnapshot.Metadata;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclCompiler;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclMetadata;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class RuleBundleValidation {

    private final RuleCompilationSnapshot snapshot;

    RuleBundleValidation(RuleCompilationSnapshot snapshot) {
        this.snapshot = snapshot;
    }

    com.voltcanary.analytics.vcl.CompilationOwnerValidation bundleValidation(
        VclCompiler compiler,
        RuleCompilationSnapshots.PreparedStart prepared,
        List<com.voltcanary.vcl.VclCircleRevision> circles
    ) {
        var compilation = prepared.compilation();
        var checkpoint = prepared.checkpoint();
        var sustained = snapshot.evaluatorNodes("sustained");
        var windows = snapshot.evaluatorNodes("window_aggregate");
        var intervals = snapshot.evaluatorNodes("interval_union_window");
        var sessions = snapshot.evaluatorNodes("window_session");
        var zones = snapshot.evaluatorNodes("zone_session");
        if (zones.isEmpty() != circles.isEmpty()) throw new VclException(
            "SPATIAL_REVISION_UNAVAILABLE",
            ""
        );
        if (
            sustained.size() + windows.size() + intervals.size() + sessions.size() + zones.size() !=
                1 ||
            checkpoint.source().sources().size() != 1 ||
            ((!sustained.isEmpty() || !sessions.isEmpty() || !zones.isEmpty()) &&
                prepared.lookups() != null) ||
            compilation
                .dependencies()
                .stream()
                .filter(dep -> dep.kind() == VclMetadata.Resource.BINDING)
                .count() != 1
        ) {
            throw new VclException("EVALUATOR_PROFILE_UNAVAILABLE", "");
        }
        String profile;
        String subjectField;
        long maxRows;
        long fanout;
        if (!sessions.isEmpty() || !zones.isEmpty()) {
            snapshot.sessionEvaluatorPlanDigest(
                compiler,
                () ->
                    circles
                        .stream()
                        .map(circle -> {
                            var shape = circle.geometry();
                            var geometry = new SpatialFeatureSnapshot.Circle(
                                circle.id(),
                                circle.revision(),
                                new SpatialFeatureSnapshot.Point(
                                    shape.longitude(),
                                    shape.latitude()
                                ),
                                new java.math.BigDecimal(shape.radiusMetres())
                            );
                            return new SpatialFeatureSnapshot(
                                snapshot.sessionScope(),
                                Map.of(circle.id(), geometry)
                            );
                        })
                        .toList(),
                checkpoint.digest()
            );
            var sessionId = zones.isEmpty() ? sessions.getFirst() : zones.getFirst();
            var session = compilation
                .graph()
                .stream()
                .filter(node -> node.id().equals(sessionId))
                .findFirst()
                .orElseThrow()
                .config();
            subjectField =
                session.path("subjectFieldId").textValue() +
                "\n" +
                session.path("occurrenceFieldId").textValue();
            profile = zones.isEmpty()
                ? com.voltcanary.analytics.vcl.DurableWindowSession.PROFILE
                : com.voltcanary.analytics.vcl.DurableZoneSession.PROFILE;
            maxRows = VclDurationWitnessKernel.MAX_INPUTS;
            fanout = 1;
        } else if (!intervals.isEmpty()) {
            var node = intervals.getFirst();
            if (prepared.lookups() == null) {
                snapshot.intervalEvaluatorPlanDigest(
                    compilation,
                    snapshot.new Metadata(),
                    null,
                    node,
                    checkpoint.digest()
                );
            } else {
                prepared.lookups().intervalEvaluatorPlanDigest(node, checkpoint.digest());
            }
            subjectField = RuleCompilationSnapshot.intervalOperator(compilation, node)
                .config()
                .path("subjectFieldId")
                .textValue();
            profile = IntervalWindowSchedule.PROFILE;
            maxRows = VclIntervalUnionKernel.MAX_INPUTS;
            fanout = VclIntervalUnionPlan.MAX_WINDOW_FANOUT;
        } else if (windows.isEmpty()) {
            // The checkpoint digest is carried only in the validated alert intent.
            snapshot.sustainedEvaluatorPlanDigest(
                compiler,
                sustained.getFirst(),
                checkpoint.digest()
            );
            subjectField = snapshot
                .prepareSustained(compiler, sustained.getFirst())
                .subjectField()
                .toString();
            profile = DurableSustained.PROFILE;
            maxRows = VclDurationWitnessKernel.MAX_INPUTS;
            fanout = 1;
        } else {
            var batch =
                prepared.lookups() == null
                    ? snapshot.prepareJournalWindowBatch(
                          compilation,
                          snapshot.new Metadata(),
                          null,
                          windows.getFirst(),
                          64L * VclWindowAggregateKernel.MAX_INPUTS,
                          true
                      )
                    : prepared.lookups().journalAggregate(windows.getFirst());
            batch.evaluatorPlanDigest();
            subjectField = batch.subjectField;
            profile = DurableJournalWindow.PROFILE;
            maxRows = VclWindowAggregateKernel.MAX_INPUTS;
            fanout = batch.plan().windows().maximumFanout();
        }
        var input = checkpoint.source().sources().getFirst();
        var journal = snapshot.journals.get(
            new SourceBindingSnapshots.Target(input.binding(), input.schemaRevision())
        );
        long work = Math.multiplyExact(
            Math.addExact(journal.retainedObservationCount(), maxRows),
            compilation.graph().size()
        );
        work = Math.multiplyExact(work, fanout);
        var versions = new LinkedHashMap<String, String>();
        compilation.graph().forEach(node -> versions.put(node.method(), profile));
        var startPin = new ResourcePin(
            com.voltcanary.analytics.vcl.CompilationOwnerValidation.ResourceKind.RULE_START_CHECKPOINT,
            UUID.fromString(snapshot.document.spec().path("ruleId").textValue()),
            1,
            checkpoint.digest()
        );
        var resources = new ArrayList<>(List.of(snapshot.grantPin, snapshot.policyPin, startPin));
        circles.forEach(circle ->
            resources.add(
                new ResourcePin(
                    com.voltcanary.analytics.vcl.CompilationOwnerValidation.ResourceKind.SPATIAL_FEATURE,
                    circle.id(),
                    circle.revision(),
                    circle.digest()
                )
            )
        );
        var execution = new com.voltcanary.analytics.vcl.CompilationOwnerValidation.ExecutionPins(
            snapshot.grantPin.id(),
            Map.of(input.binding(), input.proof().inputGeneration()),
            checkpoint.source().boundary().toString(),
            VclCanonical.sha256(subjectField),
            checkpoint.digest(),
            resources,
            com.voltcanary.analytics.vcl.Wgs84ScalarKernel.VERSION,
            versions
        );
        var checks = compilation
            .dependencies()
            .stream()
            .map(dependency -> {
                boolean binding = dependency.kind() == VclMetadata.Resource.BINDING;
                return new com.voltcanary.analytics.vcl.CompilationOwnerValidation.DependencyCheck(
                    dependency,
                    true,
                    true,
                    binding
                        ? com.voltcanary.analytics.vcl.CompilationOwnerValidation.IndexReadiness.NOT_REQUIRED
                        : com.voltcanary.analytics.vcl.CompilationOwnerValidation.IndexReadiness.READY,
                    binding ? journal.cutDigest() : checkpoint.digest()
                );
            })
            .toList();
        long workBudget = intervals.isEmpty() ? 64 * maxRows : 1024 * maxRows;
        var cost = new com.voltcanary.analytics.vcl.CompilationOwnerValidation.Cost(
            fanout * KeyedStateStore.MAX_STATE_BYTES,
            64L * KeyedStateStore.MAX_STATE_BYTES,
            work,
            workBudget
        );
        snapshot.reauthorize(prepared.lookups() != null);
        return new com.voltcanary.analytics.vcl.CompilationOwnerValidation(
            compilation.documentDigest(),
            compilation.accessScopeDigest(),
            checks,
            execution,
            cost
        );
    }
}
