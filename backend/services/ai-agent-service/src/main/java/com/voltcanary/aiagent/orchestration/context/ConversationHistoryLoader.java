package com.voltcanary.aiagent.orchestration.context;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.aiagent.orchestration.contract.ProtocolValidator;
import com.voltcanary.aiagent.orchestration.plan.IntentPlans;
import com.voltcanary.aiagent.orchestration.plan.OrchestrationPorts.Session;
import java.time.Clock;

public final class ConversationHistoryLoader implements ContextSources.Loader {
    private final ContextSources.History history;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final JsonNode resultSchema;
    public ConversationHistoryLoader(ContextSources.History history, ObjectMapper mapper, ProtocolValidator validator, Clock clock) {
        this.history = history;
        this.mapper = mapper;
        this.clock = clock;
        // Reuse canonical message roles/kinds/limits, without inventing another transcript format.
        var projection = validator.projection("dialogue");
        var schema = mapper.createObjectNode().put("type", "object").put("additionalProperties", false);
        schema.set("$defs", projection.path("$defs"));
        schema.putObject("properties").set("messages", projection.at("/$defs/dialogue/properties/messages"));
        schema.putArray("required").add("messages");
        resultSchema = schema;
    }
    @Override
    public String id() { return "conversation.history"; }
    @Override
    public JsonNode resultSchema() { return resultSchema.deepCopy(); }
    @Override
    public ContextSources.Page load(IntentPlans.ModelInput input, Session session, JsonNode arguments, ContextSources.Position position) {
        int limit = arguments.path("limit").asInt(12);
        if (limit < 1 || limit > 12) throw IntentPlans.reject("HISTORY_PAGE_LIMIT");
        long through = position == null ? history.through(session, input.state().conversationId()) : position.through();
        long after = position == null ? 0 : position.after();
        var rows = history.page(session, input.state().conversationId(), through, after, limit + 1);
        var items = rows.stream().limit(limit).toList();
        var next = rows.size() > limit ? new ContextSources.Position(items.getLast().sequence(), through) : null;
        var data = mapper.createObjectNode();
        data.set("messages", mapper.valueToTree(items));
        return new ContextSources.Page("history:" + through, clock.instant(), data, next);
    }
}
