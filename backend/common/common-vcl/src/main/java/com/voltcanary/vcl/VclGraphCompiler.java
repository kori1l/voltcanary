package com.voltcanary.vcl;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class VclGraphCompiler {

    private record SplitBounds(String start, String end) {}

    private record Output(
        String kind,
        Map<String, VclType> fields,
        String session,
        int lookups,
        SplitBounds split
    ) {
        Output(String kind, Map<String, VclType> fields, String session, int lookups) {
            this(kind, fields, session, lookups, null);
        }
    }

    private final VclRegistry registry;
    private final VclUnits units;
    private final VclCompiler.Session owner;
    private final Map<String, JsonNode> nodes = new LinkedHashMap<>();
    private final Map<String, Output> outputs = new HashMap<>();
    private final Set<String> visiting = new HashSet<>();
    private final List<VclCompilation.Node> compiled = new ArrayList<>();
    private JsonNode rule;

    VclGraphCompiler(VclRegistry registry, VclUnits units, VclCompiler.Session owner) {
        this.registry = registry;
        this.units = units;
        this.owner = owner;
    }

    List<VclCompilation.Node> compile(JsonNode rule) {
        this.rule = rule;
        owner.permit(
            VclMetadata.Resource.EXECUTION_GRANT,
            rule.path("executionGrantId").asText(),
            "/spec/executionGrantId"
        );
        positive(rule.at("/coverage/maxGapUs"), "/spec/coverage/maxGapUs");
        positive(rule.at("/timing/idleTimeoutUs"), "/spec/timing/idleTimeoutUs");
        duration(rule.at("/timing/allowedLatenessUs"), "/spec/timing/allowedLatenessUs");
        duration(rule.at("/timing/maxFutureSkewUs"), "/spec/timing/maxFutureSkewUs");
        int effects = 0;
        for (var node : rule.path("nodes")) {
            String id = node.path("id").asText();
            VclException.require(
                nodes.putIfAbsent(id, node) == null,
                "DUPLICATE_NODE_ID",
                "/spec/nodes"
            );
            if (node.path("method").asText().equals("emit_alert")) {
                effects++;
                VclException.require(
                    id.equals(rule.path("terminal").asText()),
                    "TERMINAL_EFFECT_COUNT",
                    "/spec/terminal"
                );
            }
        }
        VclException.require(effects == 1, "TERMINAL_EFFECT_COUNT", "/spec/terminal");
        resolve(rule.path("terminal").asText());
        VclException.require(outputs.size() == nodes.size(), "GRAPH_UNREACHABLE", "/spec/nodes");
        checkProofs();
        return List.copyOf(compiled);
    }

    private Output resolve(String id) {
        VclException.require(nodes.containsKey(id), "GRAPH_REFERENCE_UNKNOWN", "/spec/nodes");
        VclException.require(!visiting.contains(id), "GRAPH_CYCLE", "/spec/nodes");
        if (outputs.containsKey(id)) return outputs.get(id);
        visiting.add(id);
        var node = nodes.get(id);
        String method = node.path("method").asText();
        var descriptor = registry.operators().get(method);
        VclException.require(descriptor != null, "METHOD_UNKNOWN", "/spec/nodes");
        var inputs = new LinkedHashMap<String, Output>();
        node.path("inputs")
            .fields()
            .forEachRemaining(e -> inputs.put(e.getKey(), resolve(e.getValue().asText())));
        VclException.require(
            inputs.keySet().equals(descriptor.ports()),
            "PORT_TYPE_MISMATCH",
            "/spec/nodes"
        );
        var c = node.path("config");
        String path = "/spec/nodes/" + new ArrayList<>(nodes.keySet()).indexOf(id) + "/config";
        Output output = switch (method) {
            case "read_stream" -> new Output(
                "EVENT",
                owner.fields(owner.binding(c, path).path("fields"), path),
                null,
                0
            );
            case "lookup_asof" -> lookup(input(inputs, "events", "EVENT", path), c, path);
            case "filter" -> {
                var stream = input(inputs, "events", "EVENT", path);
                owner.expressions.predicate(
                    c.path("predicate"),
                    stream.fields(),
                    path + "/predicate"
                );
                yield stream;
            }
            case "zone_session", "window_session" -> session(
                input(inputs, "events", "EVENT", path),
                c,
                method,
                id,
                path
            );
            case "observed_within" -> {
                var session = input(inputs, "sessions", "SESSION", path);
                owner.expressions.predicate(
                    c.path("predicate"),
                    session.fields(),
                    path + "/predicate"
                );
                duration(c.path("minimumDurationUs"), path);
                yield new Output("WITNESS", session.fields(), session.session(), session.lookups());
            }
            case "absent_within" -> {
                var session = input(inputs, "sessions", "SESSION", path);
                var witness = input(inputs, "witnesses", "WITNESS", path);
                VclException.require(
                    session.session().equals(witness.session()),
                    "SESSION_MISMATCH",
                    path
                );
                VclException.require(
                    c
                        .path("allowedLatenessUs")
                        .asText()
                        .equals(rule.at("/timing/allowedLatenessUs").asText()),
                    "LATENESS_MISMATCH",
                    path
                );
                yield new Output(
                    "DECISION",
                    session.fields(),
                    session.session(),
                    session.lookups()
                );
            }
            case "sustained" -> {
                var stream = input(inputs, "events", "EVENT", path);
                VclException.require(
                    stream.split() == null,
                    "INTERVAL_LOOKUP_CONSUMER_UNSUPPORTED",
                    path
                );
                identity(stream, c.path("subjectFieldId").asText(), path);
                owner.expressions.predicate(
                    c.path("predicate"),
                    stream.fields(),
                    path + "/predicate"
                );
                positive(c.path("minimumDurationUs"), path);
                long gap = positive(c.path("maxGapUs"), path);
                VclException.require(
                    gap <= duration(rule.at("/coverage/maxGapUs"), path),
                    "GAP_POLICY_WEAKENED",
                    path
                );
                yield new Output("DECISION", stream.fields(), null, stream.lookups());
            }
            case "interval_union_window", "window_aggregate" -> measure(
                input(inputs, "events", "EVENT", path),
                c,
                method,
                path
            );
            case "compare" -> {
                var measures = input(inputs, "measures", "MEASURE", path);
                owner.expressions.predicate(
                    c.path("predicate"),
                    measures.fields(),
                    path + "/predicate"
                );
                yield new Output("DECISION", measures.fields(), null, measures.lookups());
            }
            case "emit_alert" -> {
                var decisions = input(inputs, "decisions", "DECISION", path);
                owner.permit(
                    VclMetadata.Resource.EVIDENCE_POLICY,
                    c.path("evidencePolicyId").asText(),
                    path
                );
                yield new Output(
                    "ALERT",
                    decisions.fields(),
                    decisions.session(),
                    decisions.lookups()
                );
            }
            default -> throw new VclException("METHOD_UNKNOWN", path);
        };
        VclException.require(output.fields().size() <= 32, "RUNTIME_FIELD_LIMIT", path);
        visiting.remove(id);
        outputs.put(id, output);
        var links = new LinkedHashMap<String, String>();
        node.path("inputs")
            .fields()
            .forEachRemaining(e -> links.put(e.getKey(), e.getValue().asText()));
        compiled.add(
            new VclCompilation.Node(
                id,
                method,
                links,
                c,
                output.kind(),
                output.fields(),
                output.session()
            )
        );
        return output;
    }

    private Output lookup(Output input, JsonNode c, String path) {
        VclException.require(input.lookups() < 2, "LOOKUP_DEPTH_LIMIT", path);
        var dataset = owner.dataset(c.path("target"), path + "/target");
        VclException.require(
            dataset.at("/runtimeProjection/supportsAsOf").asBoolean(false),
            "PROJECTION_UNAVAILABLE",
            path
        );
        var records = owner.fields(dataset.path("fields"), path);
        VclCompiler.unique(c.path("keyPairs"), "recordFieldId", path);
        for (var key : c.path("keyPairs")) {
            VclExpressionTypes.field(
                input.fields(),
                key.path("eventFieldId").asText(),
                path
            ).compatible(
                VclExpressionTypes.field(records, key.path("recordFieldId").asText(), path),
                path
            );
        }
        var fields = new LinkedHashMap<>(input.fields());
        var selected = new HashSet<String>();
        for (var key : c.path("select")) {
            String name = key.asText();
            VclException.require(selected.add(name), "DUPLICATE_FIELD", path);
            var type = VclExpressionTypes.field(records, name, path);
            var old = fields.putIfAbsent(name, type);
            VclException.require(old == null || old.equals(type), "FIELD_SCOPE_COLLISION", path);
        }
        var split = input.split();
        if (c.at("/temporal/mode").asText().equals("INTERVAL_SPLIT")) {
            var bounds = new SplitBounds(
                c.at("/temporal/startFieldId").asText(),
                c.at("/temporal/endFieldId").asText()
            );
            VclException.require(
                !bounds.start().equals(bounds.end()) && (split == null || split.equals(bounds)),
                "INTERVAL_LOOKUP_BOUNDS_CHANGED",
                path
            );
            VclExpressionTypes.require(
                VclExpressionTypes.field(input.fields(), bounds.start(), path),
                VclType.Kind.INSTANT,
                path
            );
            VclExpressionTypes.require(
                VclExpressionTypes.field(input.fields(), bounds.end(), path),
                VclType.Kind.INSTANT,
                path
            );
            split = bounds;
        }
        VclException.require(
            split == null || (!selected.contains(split.start()) && !selected.contains(split.end())),
            "INTERVAL_LOOKUP_BOUNDS_CHANGED",
            path
        );
        return new Output("EVENT", Map.copyOf(fields), null, input.lookups() + 1, split);
    }

    private Output session(Output input, JsonNode c, String method, String id, String path) {
        VclException.require(input.split() == null, "INTERVAL_LOOKUP_CONSUMER_UNSUPPORTED", path);
        identity(input, c.path("subjectFieldId").asText(), path);
        identity(input, c.path("occurrenceFieldId").asText(), path);
        boundedDuration(c.path("maxDurationUs"), path);
        if (method.equals("window_session")) {
            owner.expressions.predicate(c.path("startWhen"), input.fields(), path + "/startWhen");
            owner.expressions.predicate(c.path("closeWhen"), input.fields(), path + "/closeWhen");
        } else {
            VclExpressionTypes.require(
                VclExpressionTypes.field(input.fields(), c.path("positionFieldId").asText(), path),
                VclType.Kind.POINT,
                path
            );
            var zone = VclExpressionTypes.field(
                input.fields(),
                c.path("zoneFieldId").asText(),
                path
            );
            VclException.require(
                zone.kind() == VclType.Kind.REF &&
                    "RESOURCE:SPATIAL_FEATURE".equals(zone.referenceTarget()),
                "REF_TYPE_MISMATCH",
                path
            );
            if (!c.path("accuracyFieldId").isNull()) {
                var accuracy = VclExpressionTypes.field(
                    input.fields(),
                    c.path("accuracyFieldId").asText(),
                    path
                );
                VclException.require(
                    accuracy.kind() == VclType.Kind.DECIMAL && accuracy.unit().equals("m"),
                    "UNIT_MISMATCH",
                    path
                );
            }
            VclException.require(
                !c.path("accuracyFallback").asText().equals("APPROVED_FLOOR") ||
                    c.path("uncertaintyFloorMetres").doubleValue() > 0,
                "ACCURACY_POLICY_INVALID",
                path
            );
            duration(c.path("exitDebounceUs"), path);
        }
        return new Output("SESSION", input.fields(), id, input.lookups());
    }

    private Output measure(Output input, JsonNode c, String method, String path) {
        if (input.split() != null) {
            VclException.require(
                method.equals("interval_union_window"),
                "INTERVAL_LOOKUP_CONSUMER_UNSUPPORTED",
                path
            );
            VclException.require(
                input
                    .split()
                    .equals(
                        new SplitBounds(
                            c.path("startFieldId").asText(),
                            c.path("endFieldId").asText()
                        )
                    ),
                "INTERVAL_LOOKUP_BOUNDS_CHANGED",
                path
            );
        }
        var subject = identity(input, c.path("subjectFieldId").asText(), path);
        VclType measure;
        if (method.equals("interval_union_window")) {
            VclExpressionTypes.require(
                VclExpressionTypes.field(input.fields(), c.path("startFieldId").asText(), path),
                VclType.Kind.INSTANT,
                path
            );
            VclExpressionTypes.require(
                VclExpressionTypes.field(input.fields(), c.path("endFieldId").asText(), path),
                VclType.Kind.INSTANT,
                path
            );
            owner.expressions.predicate(
                c.path("includeWhere"),
                input.fields(),
                path + "/includeWhere"
            );
            owner.expressions.predicate(
                c.path("excludeWhere"),
                input.fields(),
                path + "/excludeWhere"
            );
            zone(c.path("timezone").asText(), path);
            measure = new VclType(VclType.Kind.DURATION, false, "1", null);
        } else {
            String reducer = c.path("reducer").asText();
            VclException.require(
                reducer.equals("COUNT") == c.path("valueFieldId").isNull(),
                "REDUCER_FIELD_MISMATCH",
                path
            );
            measure = reducer.equals("COUNT")
                ? new VclType(VclType.Kind.INT64, false, "1", null)
                : VclExpressionTypes.field(input.fields(), c.path("valueFieldId").asText(), path);
            if (Set.of("SUM", "AVG").contains(reducer)) VclException.require(
                measure.numeric() || measure.kind() == VclType.Kind.DURATION,
                "TYPE_MISMATCH",
                path
            );
            if (reducer.equals("AVG") && measure.kind() == VclType.Kind.INT64) {
                measure = new VclType(
                    VclType.Kind.DECIMAL,
                    measure.nullable(),
                    measure.unit(),
                    null
                );
            }
            if (reducer.equals("SUM") && measure.numeric()) VclException.require(
                !units.require(measure.unit(), path).absolute(),
                "UNIT_ARITHMETIC_UNSUPPORTED",
                path
            );
            if (Set.of("MIN", "MAX").contains(reducer)) VclException.require(
                Set.of(
                    VclType.Kind.INT64,
                    VclType.Kind.DECIMAL,
                    VclType.Kind.DURATION,
                    VclType.Kind.DATE,
                    VclType.Kind.INSTANT,
                    VclType.Kind.TEXT
                ).contains(measure.kind()),
                "TYPE_NOT_ORDERED",
                path
            );
            var window = c.path("window");
            if (window.path("kind").asText().equals("CALENDAR")) zone(
                window.path("timezone").asText(),
                path
            );
            else {
                long size = boundedDuration(window.path("sizeUs"), path);
                VclValues.instant(window.path("alignment").asText(), path);
                if (window.path("kind").asText().equals("ROLLING")) {
                    long step = positive(window.path("stepUs"), path);
                    VclException.require(
                        step <= size && (size + step - 1) / step <= 64,
                        "WINDOW_FANOUT_LIMIT",
                        path
                    );
                }
            }
        }
        String subjectId = c.path("subjectFieldId").asText();
        String measureId = c.path("measureFieldId").asText();
        VclException.require(!input.fields().containsKey(measureId), "FIELD_SCOPE_COLLISION", path);
        return new Output(
            "MEASURE",
            Map.of(subjectId, subject, measureId, measure),
            null,
            input.lookups()
        );
    }

    private void checkProofs() {
        for (var entry : nodes.entrySet()) {
            var config = entry.getValue().path("config");
            if (!config.path("emit").asText().equals("LOWER_BOUND_PROVEN")) continue;
            VclException.require(
                entry.getValue().path("method").asText().equals("interval_union_window") ||
                    config.path("reducer").asText().equals("COUNT"),
                "LOWER_BOUND_UNSUPPORTED",
                "/spec/nodes"
            );
            for (var node : nodes.values()) {
                if (!node.at("/inputs/measures").asText().equals(entry.getKey())) continue;
                var predicate = node.at("/config/predicate");
                VclException.require(
                    predicate.path("op").asText().equals("CALL") &&
                        Set.of("gt", "ge").contains(predicate.path("method").asText()) &&
                        predicate.at("/args/0/op").asText().equals("FIELD") &&
                        predicate
                            .at("/args/0/fieldId")
                            .asText()
                            .equals(config.path("measureFieldId").asText()) &&
                        predicate.at("/args/1/op").asText().equals("CONST"),
                    "LOWER_BOUND_UNSUPPORTED",
                    "/spec/nodes"
                );
            }
        }
    }

    private static Output input(Map<String, Output> inputs, String port, String kind, String path) {
        var value = inputs.get(port);
        VclException.require(
            value != null && value.kind().equals(kind),
            "PORT_TYPE_MISMATCH",
            path
        );
        return value;
    }

    private static VclType identity(Output input, String field, String path) {
        var type = VclExpressionTypes.field(input.fields(), field, path);
        VclException.require(
            Set.of(
                VclType.Kind.REF,
                VclType.Kind.TEXT,
                VclType.Kind.INT64,
                VclType.Kind.ENUM
            ).contains(type.kind()),
            "IDENTITY_TYPE",
            path
        );
        return type;
    }

    static long duration(JsonNode value, String path) {
        try {
            long number = Long.parseLong(value.asText());
            VclException.require(number >= 0, "DURATION_RANGE", path);
            return number;
        } catch (NumberFormatException error) {
            throw new VclException("DURATION_RANGE", path);
        }
    }

    private static long positive(JsonNode value, String path) {
        long result = duration(value, path);
        VclException.require(result > 0, "DURATION_NOT_POSITIVE", path);
        return result;
    }

    private static long boundedDuration(JsonNode value, String path) {
        long result = positive(value, path);
        VclException.require(result <= 31L * 86_400_000_000L, "WINDOW_LIMIT", path);
        return result;
    }

    private static void zone(String id, String path) {
        try {
            ZoneId.of(id);
        } catch (java.time.DateTimeException error) {
            throw new VclException("TIMEZONE_INVALID", path);
        }
    }
}
