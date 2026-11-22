package com.voltcanary.aiagent.orchestration.contract;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

/** Source identities for an independent assessment, without planner interpretations. */
public final class AssessmentSources {

    private AssessmentSources() {}

    public static Set<String> required(JsonNode request) {
        var ids = new TreeSet<String>();
        add(ids, request.path("messageId"));
        for (var goal : request.at("/context/goals")) {
            if ("PENDING".equals(goal.path("disposition").asText())) add(
                ids,
                goal.path("sourceMessageId")
            );
        }
        return java.util.Collections.unmodifiableSet(ids);
    }

    public static String violation(JsonNode request, JsonNode response) {
        var sources = new HashMap<String, String>();
        for (var message : request.at("/context/dialogue/messages")) {
            if ("USER".equals(message.path("role").asText())) sources.put(
                message.path("messageId").asText(),
                message.path("text").asText()
            );
        }
        sources.put(request.path("messageId").asText(), request.path("message").asText());
        var cited = new HashSet<String>();
        for (var check : response.path("checks")) {
            String id = check.path("sourceMessageId").asText();
            String source = sources.get(id);
            String quote = check.path("sourceQuote").asText();
            if (
                source == null || quote.isBlank() || !source.contains(quote)
            ) return "ASSESSMENT_SOURCE_EVIDENCE_INVALID";
            cited.add(id);
        }
        if (
            "SATISFIED".equals(response.path("verdict").asText()) &&
            !cited.containsAll(required(request))
        ) return "ASSESSMENT_SOURCE_COVERAGE_INCOMPLETE";
        return null;
    }

    private static void add(Set<String> ids, JsonNode value) {
        if (value.isTextual() && !value.textValue().isBlank()) ids.add(value.textValue());
    }
}
