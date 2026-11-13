package com.voltcanary.analytics.vcl.adapter;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voltcanary.analytics.vcl.ScalarDecimalPolicy;
import com.voltcanary.analytics.vcl.ScalarValue;
import com.voltcanary.analytics.vcl.VclScalarEvaluator;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Checked terminal decision of the direct journal window graph profile. */
record JournalWindowDecision(String nodeId, String terminalId, String digest, UUID subjectField, UUID measureField,
        ScalarValue subject, ScalarDecimalPolicy policy, VclScalarProgram program) {
    private static final VclParser PARSER = new VclParser();
    private static final VclRegistry REGISTRY = new VclRegistry(PARSER);
    private static final VclUnits UNITS = VclUnits.standard();

    static JournalWindowDecision compile(VclCompilation compilation, VclCompilation.Node aggregate,
            ScalarDecimalPolicy policy, VclMetadata owner, String subject) {
        var value = ScalarValue.known(PARSER, JsonNodeFactory.instance.objectNode().put("type", "TEXT").put("value", subject).toString());
        return compileTyped(compilation, aggregate, policy, owner, value);
    }

    static JournalWindowDecision compileTyped(VclCompilation compilation, VclCompilation.Node aggregate,
            ScalarDecimalPolicy policy, VclMetadata owner, ScalarValue subject) {
        var unbound = compileShape(compilation, aggregate, policy, owner);
        if (subject.status() != ScalarValue.Status.OK || subject.isNull()
                || !aggregate.fields().get(unbound.subjectField().toString()).kind().name()
                        .equals(subject.literal().path("type").textValue())) {
            throw new VclException("WINDOW_SUBJECT_TYPE_UNSUPPORTED", "");
        }
        return new JournalWindowDecision(unbound.nodeId(), unbound.terminalId(), unbound.digest(), unbound.subjectField(),
                unbound.measureField(), subject, unbound.policy(), unbound.program());
    }

    static JournalWindowDecision compileShape(VclCompilation compilation, VclCompilation.Node aggregate,
            ScalarDecimalPolicy policy, VclMetadata owner) {
        long projections = compilation.graph().stream().filter(node ->
                java.util.Set.of("filter", "lookup_asof").contains(node.method())).count();
        if (compilation.graph().size() != 4 + projections) throw new VclException("WINDOW_GRAPH_UNSUPPORTED", "");
        var compare = compilation.graph().stream().filter(node -> "compare".equals(node.method())
                && aggregate.id().equals(node.inputs().get("measures"))).findFirst()
                .orElseThrow(() -> new VclException("WINDOW_GRAPH_UNSUPPORTED", ""));
        var terminal = compilation.graph().stream().filter(node -> "emit_alert".equals(node.method())
                && compare.id().equals(node.inputs().get("decisions"))).findFirst()
                .orElseThrow(() -> new VclException("WINDOW_GRAPH_UNSUPPORTED", ""));
        var types = new LinkedHashMap<UUID, VclType>();
        aggregate.fields().forEach((id, type) -> types.put(UUID.fromString(id), type));
        var program = new VclScalarCompiler(PARSER, REGISTRY, UNITS)
                .compile(compare.config().path("predicate").toString(), types, owner);
        var config = aggregate.config();
        UUID subjectField = UUID.fromString(config.path("subjectFieldId").textValue());
        UUID measureField = UUID.fromString(config.path("measureFieldId").textValue());
        String digest = VclCanonical.sha256("VCL_JOURNAL_WINDOW_GRAPH/1\n" + compilation.semanticDigest()
                + "\n" + compilation.registryDigest() + "\n" + compilation.unitRegistryDigest()
                + "\n" + compilation.accessScopeDigest() + "\n" + compare.id() + "\n" + terminal.id());
        return new JournalWindowDecision(compare.id(), terminal.id(), digest, subjectField, measureField, null, policy, program);
    }

    ObjectNode evaluateLowerBound(ScalarValue measure) {
        var result = evaluate(measure);
        var verdict = result.at("/literal/value");
        return verdict.isBoolean() && !verdict.booleanValue()
                ? evaluate(ScalarValue.unknown("WINDOW_LOWER_BOUND_INSUFFICIENT", List.of()))
                : result;
    }

    ObjectNode evaluate(ScalarValue measure) {
        var evaluated = new VclScalarEvaluator(UNITS, REGISTRY.digest()).evaluate(program,
                Map.of(subjectField, subject, measureField, measure), policy, program.scopeDigest());
        var decision = JsonNodeFactory.instance.objectNode().put("nodeId", nodeId).put("terminalId", terminalId)
                .put("status", evaluated.status().name()).put("reason", evaluated.reason());
        decision.set("literal", evaluated.literal());
        var evidence = decision.putArray("evidence");
        evaluated.evidence().forEach(id -> evidence.add(id.toString()));
        return decision;
    }
}
