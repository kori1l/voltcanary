package com.voltcanary.analytics.vcl;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclCompilation;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclMetadata;
import com.voltcanary.vcl.VclParser;
import com.voltcanary.vcl.VclRegistry;
import com.voltcanary.vcl.VclScalarCompiler;
import com.voltcanary.vcl.VclScalarProgram;
import com.voltcanary.vcl.VclType;
import com.voltcanary.vcl.VclUnits;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Checked calendar and predicate projection for interval_union_window. */
public final class VclIntervalUnionPlan {
    private static final VclParser PARSER = new VclParser();
    private static final VclRegistry REGISTRY = new VclRegistry(PARSER);
    private static final VclUnits UNITS = VclUnits.standard();
    private final VclScalarEvaluator evaluator = new VclScalarEvaluator(UNITS, REGISTRY.digest());

    public record Row(UUID observation, Map<UUID, ScalarValue> fields, ScalarValue eligibility) {
        public Row { fields = Map.copyOf(fields); }
        public Row(UUID observation, Map<UUID, ScalarValue> fields) { this(observation, fields, null); }
    }

    private final String digest;
    private final String scope;
    private final UUID subjectField;
    private final UUID startField;
    private final UUID endField;
    private final VclWindowPlan windows;
    private final VclScalarProgram include;
    private final VclScalarProgram exclude;
    private final ScalarDecimalPolicy decimal;

    private VclIntervalUnionPlan(VclCompilation compilation, VclCompilation.Node node, VclMetadata owner) {
        scope = compilation.accessScopeDigest();
        if (!scope.equals(owner.accessScopeDigest())) throw new VclException("ACCESS_SCOPE_CHANGED", "");
        var source = compilation.graph().stream().filter(input -> input.id().equals(node.inputs().get("events"))
                && "EVENT".equals(input.outputKind())).findFirst()
                .orElseThrow(() -> new VclException("INTERVAL_INPUT_REQUIRED", ""));
        var fields = new LinkedHashMap<UUID, VclType>();
        source.fields().forEach((id, type) -> fields.put(UUID.fromString(id), type));
        var config = node.config();
        subjectField = UUID.fromString(config.path("subjectFieldId").textValue());
        startField = UUID.fromString(config.path("startFieldId").textValue());
        endField = UUID.fromString(config.path("endFieldId").textValue());
        windows = VclWindowPlan.compileIntervalUnion(node);
        var compiler = new VclScalarCompiler(PARSER, REGISTRY, UNITS);
        include = compiler.compile(config.path("includeWhere").toString(), fields, owner);
        exclude = compiler.compile(config.path("excludeWhere").toString(), fields, owner);
        var policy = PARSER.parse(compilation.canonicalDocument()).spec().path("decimalPolicy");
        decimal = new ScalarDecimalPolicy(policy.path("precision").intValue(), policy.path("scale").intValue(),
                ScalarDecimalPolicy.Rounding.valueOf(policy.path("rounding").textValue()));
        digest = VclCanonical.sha256(String.join("\n", VclIntervalUnionKernel.VERSION,
                compilation.semanticDigest(), node.id(), windows.digest(), scope));
    }

    public static VclIntervalUnionPlan compile(VclCompilation compilation, String nodeId, VclMetadata owner) {
        var node = compilation.graph().stream().filter(candidate -> candidate.id().equals(nodeId)
                && "interval_union_window".equals(candidate.method())).findFirst()
                .orElseThrow(() -> new VclException("INTERVAL_OPERATOR_REQUIRED", ""));
        return new VclIntervalUnionPlan(compilation, node, owner);
    }

    public String digest() { return digest; }
    public VclWindowPlan.Window windowAt(Instant time) { return windows.assign(time).getFirst(); }
    public UUID subjectField() { return subjectField; }

    public List<VclWindowPlan.Window> overlappingWindows(Instant start, Instant end) {
        if (!start.isBefore(end)) throw new VclException("INTERVAL_REVERSED", "");
        var result = new ArrayList<VclWindowPlan.Window>();
        for (var window = windowAt(start); window.startInclusive().isBefore(end); window = windowAt(window.endExclusive())) {
            if (result.size() == 64) throw new VclException("WINDOW_FANOUT_LIMIT", "");
            result.add(window);
        }
        return List.copyOf(result);
    }

    public Instant reportingHorizon(VclWindowPlan.Window window, com.fasterxml.jackson.databind.JsonNode sourceDefinition) {
        return reportingHorizon(window, window.endExclusive(), sourceDefinition);
    }

    public Instant reportingHorizon(VclWindowPlan.Window window, Instant measuredThrough,
            com.fasterxml.jackson.databind.JsonNode sourceDefinition) {
        if (!window.equals(windowAt(window.startInclusive()))) throw new VclException("INTERVAL_WINDOW_CHANGED", "");
        prefix(window, measuredThrough);
        var horizon = measuredThrough.plus(reportSpanUs(sourceDefinition), java.time.temporal.ChronoUnit.MICROS);
        VclWindowPlan.micros(horizon);
        return horizon;
    }

    public long maximumReportingRangeUs(com.fasterxml.jackson.databind.JsonNode sourceDefinition) {
        long span = windows.maximumSpanUs();
        if (span > 32L * 86_400_000_000L) throw new VclException("INTERVAL_WINDOW_LIMIT", "");
        return span + reportSpanUs(sourceDefinition);
    }

    private long reportSpanUs(com.fasterxml.jackson.databind.JsonNode sourceDefinition) {
        var contract = com.voltcanary.vcl.VclSourceBindingProfile.intervalFields(sourceDefinition)
                .orElseThrow(() -> new VclException("INTERVAL_REPORT_CONTRACT_REQUIRED", ""));
        if (!startField.equals(contract.start()) || !endField.equals(contract.end())) {
            throw new VclException("INTERVAL_REPORT_BOUNDS_CHANGED", "");
        }
        return contract.maxSpanUs();
    }

    public ScalarValue evaluate(String subjectDigest, VclWindowPlan.Window window, List<Row> rows, String authorizedScope) {
        return evaluatePrefix(subjectDigest, window, window.endExclusive(), rows, authorizedScope);
    }

    public ScalarValue evaluatePrefix(String subjectDigest, VclWindowPlan.Window window, Instant measuredThrough,
            List<Row> rows, String authorizedScope) {
        if (!scope.equals(authorizedScope)) throw new VclException("ACCESS_SCOPE_CHANGED", "");
        if (!window.equals(windowAt(window.startInclusive()))) throw new VclException("INTERVAL_WINDOW_CHANGED", "");
        var clipped = prefix(window, measuredThrough);
        if (rows.size() > VclIntervalUnionKernel.MAX_INPUTS) throw new VclException("INTERVAL_INPUT_LIMIT", "");
        var inputs = new ArrayList<VclIntervalUnionKernel.Input>();
        var ids = new java.util.HashSet<UUID>();
        for (var row : rows) {
            if (!ids.add(row.observation())) throw new VclException("INTERVAL_INPUT_DUPLICATE", "");
            var subject = field(row, subjectField);
            if (subject.isFailure()) return subject;
            boolean unresolved = subject.status() == ScalarValue.Status.UNKNOWN || subject.isNull();
            if (!unresolved && !subjectDigest.equals(VclWindowSessionKernel.identity(subject))) continue;
            var included = evaluator.evaluate(include, row.fields(), decimal, authorizedScope);
            var excluded = evaluator.evaluate(exclude, row.fields(), decimal, authorizedScope);
            var interval = interval(row);
            if (row.eligibility() != null) {
                if (row.eligibility().isFailure()) return row.eligibility();
                if (!interval.isFailure()) interval = row.eligibility();
            }
            if (unresolved && !interval.isFailure()) {
                interval = ScalarValue.unknown("INTERVAL_SUBJECT_UNKNOWN", List.of(row.observation()));
            }
            inputs.add(new VclIntervalUnionKernel.Input(row.observation(), interval, included, excluded));
        }
        return new VclIntervalUnionKernel().evaluate(new VclIntervalUnionKernel.Context(VclIntervalUnionKernel.VERSION,
                scope, clipped), inputs, authorizedScope);
    }

    private static VclWindowPlan.Window prefix(VclWindowPlan.Window window, Instant through) {
        if (through.isAfter(window.endExclusive())) throw new VclException("INTERVAL_PREFIX_OUTSIDE_WINDOW", "");
        return new VclWindowPlan.Window(window.startInclusive(), through);
    }

    private ScalarValue interval(Row row) {
        var start = field(row, startField);
        var end = field(row, endField);
        if (start.isFailure()) return start;
        if (end.isFailure()) return end;
        if (start.status() == ScalarValue.Status.UNKNOWN || start.isNull()
                || end.status() == ScalarValue.Status.UNKNOWN || end.isNull()) {
            return ScalarValue.unknown("INTERVAL_BOUND_UNKNOWN", List.of(row.observation()));
        }
        if (!"INSTANT".equals(start.literal().path("type").textValue())
                || !"INSTANT".equals(end.literal().path("type").textValue())) {
            return ScalarValue.failure(ScalarValue.Status.INVALID_INPUT, "TYPE_MISMATCH");
        }
        var literal = JsonNodeFactory.instance.objectNode().put("type", "INTERVAL");
        var bounds = literal.putObject("value");
        bounds.set("start", start.literal().path("value"));
        bounds.set("end", end.literal().path("value"));
        return ScalarValue.known(literal);
    }

    private static ScalarValue field(Row row, UUID field) {
        return row.fields().getOrDefault(field, ScalarValue.unknown("INTERVAL_FIELD_MISSING", List.of(row.observation())));
    }
}
