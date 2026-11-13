package com.voltcanary.analytics.vcl.adapter;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclParser;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.UUID;

/** Credential-free input identities for rebuilding a reviewed RULE start checkpoint. */
record RuleStartCheckpointRecipe(String payload, String digest) {
    private static final VclParser PARSER = new VclParser();

    RuleStartCheckpointRecipe {
        if (payload == null || payload.getBytes(StandardCharsets.UTF_8).length > VclParser.MAX_BYTES
                || !VclCanonical.sha256(payload).equals(digest)) {
            throw new VclException("START_CHECKPOINT_RECIPE_INVALID", "");
        }
    }

    static RuleStartCheckpointRecipe from(RuleCompilationSnapshots.StartCheckpoint checkpoint) {
        var value = JsonNodeFactory.instance.objectNode().put("profile", "RULE_START_RECIPE/1")
                .put("documentDigest", checkpoint.documentDigest()).put("checkpointDigest", checkpoint.digest())
                .put("from", checkpoint.source().from().toString()).put("boundary", checkpoint.source().boundary().toString());
        var sources = value.putArray("sources");
        checkpoint.source().sources().forEach(source -> sources.addObject()
                .put("binding", source.binding().toString()).put("revision", source.schemaRevision())
                .put("head", source.cut().head()).put("cut", source.cut().digest())
                .put("proof", source.proof().proofId().toString()));
        var references = value.putObject("references");
        checkpoint.references().forEach((node, pin) -> references.putObject(node)
                .put("query", pin.canonicalQuery()).put("intervalField", pin.intervalField().toString()));
        String payload = VclCanonical.encode(value);
        return new RuleStartCheckpointRecipe(payload, VclCanonical.sha256(payload));
    }

    Inputs inputs() {
        try {
            var value = PARSER.readJson(payload.getBytes(StandardCharsets.UTF_8));
            if (!"RULE_START_RECIPE/1".equals(value.path("profile").textValue())
                    || !payload.equals(VclCanonical.encode(value))) throw new IllegalArgumentException();
            var cuts = new LinkedHashMap<SourceBindingSnapshots.Target, SourceCaptureSnapshot.Cut>();
            var proofs = new ArrayList<RuleCompilationSnapshot.SourceStartProof>();
            for (var source : value.path("sources")) {
                UUID binding = UUID.fromString(source.path("binding").textValue());
                long revision = source.path("revision").longValue();
                var target = new SourceBindingSnapshots.Target(binding, revision);
                var cut = new SourceCaptureSnapshot.Cut(source.path("head").textValue(), source.path("cut").textValue());
                if (cuts.putIfAbsent(target, cut) != null) throw new IllegalArgumentException();
                proofs.add(new RuleCompilationSnapshot.SourceStartProof(binding, revision,
                        UUID.fromString(source.path("proof").textValue())));
            }
            var references = new LinkedHashMap<String, RuleCompilationSnapshot.LookupSelection>();
            value.path("references").fields().forEachRemaining(entry -> references.put(entry.getKey(),
                    new RuleCompilationSnapshot.LookupSelection(PARSER.parse(entry.getValue().path("query").textValue()),
                            UUID.fromString(entry.getValue().path("intervalField").textValue()))));
            return new Inputs(value.path("documentDigest").textValue(), value.path("checkpointDigest").textValue(),
                    Instant.parse(value.path("from").textValue()), Instant.parse(value.path("boundary").textValue()),
                    java.util.Map.copyOf(cuts), java.util.List.copyOf(proofs), java.util.Map.copyOf(references));
        } catch (RuntimeException invalid) {
            throw new VclException("START_CHECKPOINT_RECIPE_INVALID", "");
        }
    }

    record Inputs(String documentDigest, String checkpointDigest, Instant from, Instant boundary,
            java.util.Map<SourceBindingSnapshots.Target, SourceCaptureSnapshot.Cut> cuts,
            java.util.List<RuleCompilationSnapshot.SourceStartProof> proofs,
            java.util.Map<String, RuleCompilationSnapshot.LookupSelection> references) { }

    static java.util.Map<String, RuleCompilationSnapshot.ReferenceStartPin> referencePins(com.fasterxml.jackson.databind.JsonNode node) {
        if (!node.isObject() || node.isEmpty() || node.size() > 2) throw new VclException("INTERVAL_REFERENCE_CHANGED", "");
        var pins = new LinkedHashMap<String, RuleCompilationSnapshot.ReferenceStartPin>();
        node.fields().forEachRemaining(field -> {
            var value = field.getValue();
            String query = value.path("canonicalQuery").textValue();
            String digest = value.path("manifestDigest").textValue();
            if (query == null || digest == null || !digest.matches("[0-9a-f]{64}")) {
                throw new VclException("INTERVAL_REFERENCE_CHANGED", "");
            }
            pins.put(field.getKey(), new RuleCompilationSnapshot.ReferenceStartPin(
                    query, UUID.fromString(value.path("intervalField").textValue()), digest));
        });
        return java.util.Map.copyOf(pins);
    }

    static java.util.Map<String, RuleCompilationSnapshot.LookupSelection> selections(
            java.util.Map<String, RuleCompilationSnapshot.ReferenceStartPin> pins) {
        var selections = new LinkedHashMap<String, RuleCompilationSnapshot.LookupSelection>();
        pins.forEach((id, pin) -> selections.put(id, new RuleCompilationSnapshot.LookupSelection(
                PARSER.parse(pin.canonicalQuery()), pin.intervalField())));
        return java.util.Map.copyOf(selections);
    }
}
