package com.voltcanary.aiagent.orchestration.context;

import com.fasterxml.jackson.databind.JsonNode;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.LoadedContext;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.LoaderRequest;
import com.voltcanary.aiagent.orchestration.contract.ProtocolValidator;
import com.voltcanary.aiagent.orchestration.contract.SkillRegistry;
import com.voltcanary.aiagent.orchestration.plan.IntentOrchestration;
import com.voltcanary.aiagent.orchestration.plan.IntentPlans;
import com.voltcanary.aiagent.orchestration.plan.OrchestrationPorts.Session;
import com.voltcanary.aiagent.trace.AssistantTrace;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Registered read-only capabilities. Arguments and results are validated before a continuation. */
public final class ContextLoading {
    private final SkillRegistry skills;
    private final ProtocolValidator validator;
    private final ContextSources.Cursors cursors;
    private final Map<String, ContextSources.Loader> loaders;
    private final Clock clock;
    public ContextLoading(SkillRegistry skills, ProtocolValidator validator, ContextSources.Cursors cursors,
            List<ContextSources.Loader> loaders, Clock clock) {
        this.skills = skills;
        this.validator = validator;
        this.cursors = cursors;
        this.clock = clock;
        var registered = new LinkedHashMap<String, ContextSources.Loader>();
        for (var loader : loaders) if (registered.putIfAbsent(loader.id(), loader) != null) throw new IllegalArgumentException("DUPLICATE_CONTEXT_LOADER");
        this.loaders = Map.copyOf(registered);
    }
    public JsonNode resultSchema(String id) { return loader(id).resultSchema().deepCopy(); }
    public LoadedContext load(IntentPlans.ModelInput input, Session session, IntentOrchestration.Skills access,
            String stepId, LoaderRequest request) {
        var step = input.state().step(stepId);
        var schema = skills.loaderSchema(step.intentId(), request.loaderId(), request.argumentsSchemaId(), access.authorized(), access.capabilities());
        validator.validateRegistered(schema, request.arguments());
        String binding = binding(input, session, stepId, request.loaderId(), request.arguments());
        var position = request.cursor() == null ? null : cursors.resolve(request.cursor(), binding, clock.instant());
        var loader = loader(request.loaderId());
        try (var trace = AssistantTrace.start("CONTEXT_LOAD", Map.of("loaderId", request.loaderId(), "stepId", stepId))) {
            var page = loader.load(input, session, request.arguments().deepCopy(), position);
            validator.validateRegistered(loader.resultSchema(), page.data());
            var safe = com.voltcanary.aiagent.conversation.ConversationRedaction.value(page.data(), JsonNode.class, new com.fasterxml.jackson.databind.ObjectMapper());
            if (!safe.equals(page.data())) throw IntentPlans.reject("CONTEXT_CONTAINS_CREDENTIALS");
            if (page.asOf().isAfter(clock.instant().plusSeconds(5))) throw IntentPlans.reject("CONTEXT_SNAPSHOT_INVALID");
            String cursor = page.next() == null ? null : cursors.issue(binding, page.next(), expiry(input));
            var result = new LoadedContext(request.loaderId(), page.snapshotId(), page.asOf().toString(), cursor == null ? "COMPLETE" : "PARTIAL", cursor, page.data().deepCopy());
            validator.validate("loadedContext", new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(result));
            trace.success(Map.of("snapshotId", page.snapshotId(), "completeness", result.completeness()));
            return result;
        }
    }
    public String historyCursor(IntentPlans.ModelInput input, Session session, String stepId, JsonNode arguments, long through) {
        return cursors.issue(binding(input, session, stepId, "conversation.history", arguments), new ContextSources.Position(0, through), expiry(input));
    }
    private java.time.Instant expiry(IntentPlans.ModelInput input) {
        return input.state().expiresAt().isBefore(clock.instant().plus(Duration.ofMinutes(15))) ? input.state().expiresAt() : clock.instant().plus(Duration.ofMinutes(15));
    }
    private String binding(IntentPlans.ModelInput input, Session session, String stepId, String loader, JsonNode arguments) {
        var skill = stepId == null ? null : input.state().skills().get(stepId);
        return SkillRegistry.digest(session.owner().userId() + ":" + session.owner().accountId() + ":" + input.state().conversationId()
                + ":" + input.state().planId() + ":" + stepId + ":" + skill + ":" + loader + ":" + canonical(arguments));
    }
    private JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            var sorted = new java.util.TreeMap<String, JsonNode>();
            value.fields().forEachRemaining(entry -> sorted.put(entry.getKey(), canonical(entry.getValue())));
            var result = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
            sorted.forEach(result::set);
            return result;
        }
        if (value.isArray()) {
            var result = new com.fasterxml.jackson.databind.ObjectMapper().createArrayNode();
            value.forEach(item -> result.add(canonical(item)));
            return result;
        }
        return value;
    }
    private ContextSources.Loader loader(String id) {
        var result = loaders.get(id);
        if (result == null) throw IntentPlans.reject("LOADER_UNAVAILABLE");
        return result;
    }
}
