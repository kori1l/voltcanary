package com.voltcanary.aiagent.nativeops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.aiagent.adapter.ScopedDomainContextLoaders;
import com.voltcanary.aiagent.orchestration.context.ContextSources;
import com.voltcanary.aiagent.orchestration.contract.SkillRegistry;
import com.voltcanary.aiagent.orchestration.plan.IntentPlans;
import com.voltcanary.aiagent.orchestration.plan.OrchestrationPorts.Session;
import java.time.Clock;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;

/** Projects only the readiness of a source explicitly selected by the caller. */
@RequiredArgsConstructor
public final class NativeSourceCaptureLoader implements ContextSources.Loader {
    public static final String ID = "source.capture";
    private final NativeOwnerClient owner;
    private final ObjectMapper mapper;
    private final Clock clock;

    public ScopedDomainContextLoaders.Definition definition() {
        return new ScopedDomainContextLoaders.Definition(ID, ID + ":args:v1",
                mapper.createObjectNode().put("type", "object").put("additionalProperties", false)
                        .put("description", "Readiness is provided automatically for an explicitly selected source binding."),
                this);
    }

    @Override public String id() { return ID; }
    @Override public JsonNode resultSchema() { return initialSchema(); }
    @Override public JsonNode initialSchema() {
        var schema = mapper.createObjectNode().put("type", "object").put("additionalProperties", false);
        var fields = schema.putObject("properties");
        fields.putObject("bindingId").put("type", "string").put("format", "uuid");
        fields.putObject("schemaRevision").put("type", "integer").put("minimum", 1);
        fields.putObject("snapshotId").put("type", "string").put("format", "uuid");
        fields.putObject("state").put("type", "string");
        for (var key : java.util.List.of("journalReady", "negativeEvidenceReady", "ruleExecutionReady")) {
            fields.putObject(key).put("type", "boolean");
        }
        fields.fieldNames().forEachRemaining(schema.putArray("required")::add);
        return schema;
    }

    @Override public ContextSources.Page initial(IntentPlans.ModelInput input, Session session, Supplier<JsonNode> submitted) {
        String selected = null;
        for (var item : submitted.get().path("selection")) {
            if (!"sourceBinding".equals(item.path("field").asText())) continue;
            if (selected != null) throw IntentPlans.reject("SOURCE_CAPTURE_SELECTION_INVALID");
            selected = item.path("ref").asText();
        }
        if (selected == null) return null;
        var parts = selected.split(":", -1);
        if (parts.length != 2) throw IntentPlans.reject("SOURCE_CAPTURE_SELECTION_INVALID");
        var data = read(input, session, UUID.fromString(parts[0]), Long.parseLong(parts[1]));
        return new ContextSources.Page(SkillRegistry.digest(data.toString()), clock.instant(), data, null);
    }

    @Override public void verifyInitialForDispatch(IntentPlans.ModelInput input, Session session, JsonNode data) {
        var current = read(input, session, UUID.fromString(data.path("bindingId").asText()), data.path("schemaRevision").longValue());
        if (!current.equals(data)) throw IntentPlans.reject("SOURCE_CAPTURE_CHANGED");
    }

    @Override public ContextSources.Page load(IntentPlans.ModelInput input, Session session, JsonNode arguments, ContextSources.Position position) {
        throw IntentPlans.reject("SOURCE_CAPTURE_EXPLICIT_SELECTION_REQUIRED");
    }

    private JsonNode read(IntentPlans.ModelInput input, Session session, UUID binding, long revision) {
        if (!session.owner().equals(input.token().owner())) throw IntentPlans.reject("CONTEXT_SCOPE_CHANGED");
        var description = owner.sourceCapture(session.authorization(), session.owner(), binding, revision);
        var capture = description.capture();
        return mapper.createObjectNode().put("bindingId", binding.toString()).put("schemaRevision", revision)
                .put("snapshotId", description.source().snapshotId().toString()).put("state", capture.state())
                .put("journalReady", capture.journalReady()).put("negativeEvidenceReady", capture.negativeEvidenceReady())
                .put("ruleExecutionReady", capture.ruleExecutionReady());
    }
}
