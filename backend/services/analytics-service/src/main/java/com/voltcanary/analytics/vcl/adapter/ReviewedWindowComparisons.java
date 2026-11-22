package com.voltcanary.analytics.vcl.adapter;

import com.voltcanary.analytics.vcl.ScalarValue;
import com.voltcanary.analytics.vcl.VclWindowPlan;
import com.voltcanary.analytics.vcl.VclWindowSessionKernel;
import com.voltcanary.analytics.vcl.adapter.ReviewedRuleCutovers.ComparedWindow;
import com.voltcanary.analytics.vcl.adapter.ReviewedRuleCutovers.WindowComparison;
import com.voltcanary.analytics.vcl.adapter.ReviewedRuleCutovers.WindowComparisonRequest;
import com.voltcanary.analytics.vcl.adapter.ReviewedRuleCutovers.WindowPreview;
import com.voltcanary.analytics.vcl.adapter.ReviewedRuleCutovers.WindowPreviewRequest;
import com.voltcanary.vcl.VclCompiler;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclParser;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/** Compares reviewed window evaluations against exact compilation pins. */
final class ReviewedWindowComparisons {

    private final RuleCompilationSnapshots snapshots;
    private final VclParser parser;
    private final VclCompiler compiler;
    private final ReviewedRuleCompilation compilationReads;

    ReviewedWindowComparisons(
        RuleCompilationSnapshots snapshots,
        VclParser parser,
        VclCompiler compiler,
        ReviewedRuleCompilation compilationReads
    ) {
        this.snapshots = snapshots;
        this.parser = parser;
        this.compiler = compiler;
        this.compilationReads = compilationReads;
    }

    WindowPreview previewWindow(String authorization, UUID actor, WindowPreviewRequest request) {
        if (
            request == null ||
            request.account() == null ||
            request.bundle() == null ||
            request.checkpoint() == null ||
            request.node() == null ||
            request.subjectLiteral() == null ||
            request.from() == null ||
            request.until() == null ||
            !request.from().isBefore(request.until()) ||
            Duration.between(request.from(), request.until()).compareTo(Duration.ofDays(32)) > 0
        ) {
            throw new VclException("WINDOW_PREVIEW_INVALID", "");
        }
        var window = new VclWindowPlan.Window(request.from(), request.until());
        var subject = ScalarValue.known(parser, request.subjectLiteral());
        if (subject.isNull()) throw new VclException("WINDOW_SUBJECT_INVALID", "");
        var bundle = compilationReads.bundle(request.account(), request.bundle());
        var snapshot = snapshots.verifiedStartSnapshot(
            authorization,
            actor,
            request.account(),
            compilationReads.document(bundle),
            compiler,
            Long.MAX_VALUE,
            request.checkpoint(),
            bundle
        );
        snapshot.verifyBundleOwners(bundle);
        if (request.checkpoint().references().isEmpty()) {
            var result = previewDirectWindow(
                snapshot,
                bundle.id(),
                request.node(),
                subject,
                window
            );
            snapshot.verifyBundleOwners(bundle);
            return new WindowPreview(snapshot.sourceCutDigest(), result);
        }
        var lookups = snapshots.prepareLookups(
            snapshot,
            compiler,
            authorization,
            RuleStartCheckpointRecipe.selections(request.checkpoint().references())
        );
        if (!lookups.referenceStartPins().equals(request.checkpoint().references())) {
            throw new VclException("INTERVAL_REFERENCE_CHANGED", "");
        }
        var result = compareWindow(lookups, request.node(), subject, window);
        snapshot.verifyBundleOwners(bundle);
        return new WindowPreview(lookups.sourceCutDigest(), result);
    }

    ComparedWindow previewDirectWindow(
        RuleCompilationSnapshot snapshot,
        UUID bundle,
        String node,
        ScalarValue subject,
        VclWindowPlan.Window window
    ) {
        var operator = windowOperator(snapshot.compile(compiler), node, subject);
        long budget = RuleCompilationSnapshot.MAX_RETAINED_WINDOW_BYTES;
        if ("sustained".equals(operator.method())) {
            var preview = snapshot.previewDirectSustained(
                compiler,
                node,
                bundle,
                subject,
                window,
                budget
            );
            var result = preview.result();
            var value = result.value();
            var evidence =
                result.firstObservationId() == null
                    ? value.evidence()
                    : java.util.stream.Stream.of(
                          result.firstObservationId(),
                          result.lastObservationId()
                      )
                          .distinct()
                          .toList();
            return new ComparedWindow(
                preview.inputDigest(),
                value.status().name(),
                value.reason(),
                value.literal(),
                evidence,
                Map.of()
            );
        }
        var preview = switch (operator.method()) {
            case "window_aggregate" -> snapshot
                .prepareJournalWindowBatch(
                    compiler,
                    node,
                    64L * com.voltcanary.analytics.vcl.VclWindowAggregateKernel.MAX_INPUTS,
                    true
                )
                .preview(subject, window, budget);
            case "interval_union_window" -> snapshot.previewDirectIntervalUnion(
                compiler,
                node,
                VclWindowSessionKernel.identity(subject),
                window,
                budget
            );
            default -> throw new VclException("WINDOW_OPERATOR_REQUIRED", "");
        };
        return comparedWindow(preview, Map.of());
    }

    WindowComparison compareWindows(
        String authorization,
        UUID actor,
        WindowComparisonRequest request
    ) {
        if (
            request == null ||
            request.account() == null ||
            request.bundle() == null ||
            request.node() == null ||
            request.subjectLiteral() == null ||
            request.original() == null ||
            request.original().isEmpty() ||
            request.corrected() == null ||
            !request.original().keySet().equals(request.corrected().keySet()) ||
            request.original().size() > 2
        ) throw new VclException("WINDOW_COMPARISON_INVALID", "");
        var window = new VclWindowPlan.Window(request.from(), request.until());
        var subject = ScalarValue.known(parser, request.subjectLiteral());
        if (subject.isNull()) throw new VclException("WINDOW_SUBJECT_INVALID", "");
        var document = compilationReads.document(
            compilationReads.bundle(request.account(), request.bundle())
        );
        var snapshot = snapshots.captureJournal(
            authorization,
            actor,
            request.account(),
            document,
            Long.MAX_VALUE
        );
        var original = snapshots.prepareLookups(
            snapshot,
            compiler,
            authorization,
            compilationReads.selections(request.original())
        );
        var originalValue = compareWindow(original, request.node(), subject, window);
        var corrected = snapshots.prepareLookups(
            snapshot,
            compiler,
            authorization,
            compilationReads.selections(request.corrected())
        );
        var correctedValue = compareWindow(corrected, request.node(), subject, window);
        snapshot.reauthorize();
        return new WindowComparison(original.sourceCutDigest(), originalValue, correctedValue);
    }

    ComparedWindow compareWindow(
        RulePreparedLookups lookup,
        String node,
        ScalarValue subject,
        VclWindowPlan.Window window
    ) {
        var operator = windowOperator(lookup.compilation(), node, subject);
        long budget = RuleCompilationSnapshot.MAX_RETAINED_WINDOW_BYTES;
        var preview = switch (operator.method()) {
            case "window_aggregate" -> lookup.previewWindowAggregate(node, subject, window, budget);
            case "interval_union_window" -> lookup.previewIntervalUnion(
                node,
                VclWindowSessionKernel.identity(subject),
                window,
                budget
            );
            default -> throw new VclException("WINDOW_OPERATOR_REQUIRED", "");
        };
        return comparedWindow(lookup, preview);
    }

    static com.voltcanary.vcl.VclCompilation.Node windowOperator(
        com.voltcanary.vcl.VclCompilation compilation,
        String node,
        ScalarValue subject
    ) {
        var operator = compilation
            .graph()
            .stream()
            .filter(candidate -> node.equals(candidate.id()))
            .findFirst()
            .orElseThrow(() -> new VclException("WINDOW_OPERATOR_REQUIRED", ""));
        var type = operator.fields().get(operator.config().path("subjectFieldId").textValue());
        if (
            type == null || !type.kind().name().equals(subject.literal().path("type").textValue())
        ) {
            throw new VclException("WINDOW_SUBJECT_INVALID", "");
        }
        return operator;
    }

    static ComparedWindow comparedWindow(
        RulePreparedLookups lookup,
        RuleCompilationSnapshot.WindowPreview preview
    ) {
        var manifests = new java.util.TreeMap<String, String>();
        lookup.referenceStartPins().forEach((id, pin) -> manifests.put(id, pin.manifestDigest()));
        return comparedWindow(preview, manifests);
    }

    static ComparedWindow comparedWindow(
        RuleCompilationSnapshot.WindowPreview preview,
        Map<String, String> manifests
    ) {
        var value = preview.localValue();
        return new ComparedWindow(
            preview.inputDigest(),
            value.status().name(),
            value.reason(),
            value.literal(),
            value.evidence(),
            manifests
        );
    }
}
