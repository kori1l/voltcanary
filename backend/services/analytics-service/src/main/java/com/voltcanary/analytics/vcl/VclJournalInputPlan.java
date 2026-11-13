package com.voltcanary.analytics.vcl;

import com.voltcanary.vcl.VclCompilation;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclMetadata;
import com.voltcanary.vcl.VclParser;
import com.voltcanary.vcl.VclRegistry;
import com.voltcanary.vcl.VclScalarCompiler;
import com.voltcanary.vcl.VclScalarProgram;
import com.voltcanary.vcl.VclType;
import com.voltcanary.vcl.VclUnits;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class VclJournalInputPlan {
    public enum Selection { INCLUDED, EXCLUDED, UNKNOWN }

    private static final VclParser PARSER = new VclParser();
    private static final VclRegistry REGISTRY = new VclRegistry(PARSER);
    private static final VclUnits UNITS = VclUnits.standard();
    private static final VclScalarEvaluator EVALUATOR = new VclScalarEvaluator(UNITS, REGISTRY.digest());

    private final VclCompilation.Node source;
    private final List<VclScalarProgram> filters;
    private final ScalarDecimalPolicy decimal;
    private final String scope;

    private VclJournalInputPlan(VclCompilation.Node source, List<VclScalarProgram> filters,
            ScalarDecimalPolicy decimal, String scope) {
        this.source = source;
        this.filters = List.copyOf(filters);
        this.decimal = decimal;
        this.scope = scope;
    }

    public VclCompilation.Node source() { return source; }

    public static VclJournalInputPlan compile(VclCompilation compilation, VclCompilation.Node operator, VclMetadata metadata) {
        var nodes = new HashMap<String, VclCompilation.Node>();
        compilation.graph().forEach(node -> nodes.put(node.id(), node));
        var source = nodes.get(operator.inputs().get("events"));
        var filters = new ArrayList<VclScalarProgram>();
        var compiler = new VclScalarCompiler(PARSER, REGISTRY, UNITS);
        while (source != null && "filter".equals(source.method())) {
            var fields = new LinkedHashMap<UUID, VclType>();
            source.fields().forEach((id, type) -> fields.put(UUID.fromString(id), type));
            filters.addFirst(compiler.compile(source.config().path("predicate").toString(), fields, metadata));
            source = nodes.get(source.inputs().get("events"));
        }
        if (source == null || !"read_stream".equals(source.method())) {
            throw new VclException("sustained".equals(operator.method())
                    ? "SUSTAINED_DIRECT_JOURNAL_REQUIRED" : "SESSION_DIRECT_JOURNAL_REQUIRED", "");
        }
        var document = PARSER.parse(compilation.canonicalDocument());
        var policy = document.spec().path("decimalPolicy");
        var decimal = new ScalarDecimalPolicy(policy.path("precision").intValue(), policy.path("scale").intValue(),
                ScalarDecimalPolicy.Rounding.valueOf(policy.path("rounding").textValue()));
        return new VclJournalInputPlan(source, filters, decimal, compilation.accessScopeDigest());
    }

    public Selection select(Map<UUID, ScalarValue> fields) {
        boolean unresolved = false;
        for (var filter : filters) {
            var result = EVALUATOR.evaluate(filter, fields, decimal, scope);
            if (result.isFailure()) throw new VclException(result.reason(), "");
            if (result.status() != ScalarValue.Status.OK || result.isNull()) {
                unresolved = true;
            } else if (!result.literal().path("value").booleanValue()) {
                return Selection.EXCLUDED;
            }
        }
        return unresolved ? Selection.UNKNOWN : Selection.INCLUDED;
    }
}
