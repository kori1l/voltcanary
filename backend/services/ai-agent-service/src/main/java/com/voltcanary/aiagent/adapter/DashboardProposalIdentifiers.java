package com.voltcanary.aiagent.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

/** Technical labels only. Referenced collisions stay ambiguous and must pass the existing rejection boundary. */
public final class DashboardProposalIdentifiers {

    private DashboardProposalIdentifiers() {}

    public static JsonNode normalize(JsonNode proposal) {
        if (!proposal.path("outcome").asText().equals("PREPARED")) return proposal;
        var operations = proposal.at("/command/payload/operations");
        if (!operations.isArray()) return proposal;
        var counts = new HashMap<String, Integer>();
        var referenced = new HashSet<String>();
        for (var operation : operations) {
            if (operation.path("id").isTextual()) counts.merge(
                operation.path("id").asText(),
                1,
                Integer::sum
            );
            for (String field : Set.of("target", "widgetKey", "anchor"))
                reference(operation.path(field), referenced);
            for (var target : operation.path("targets")) reference(target, referenced);
        }
        var used = new HashSet<>(counts.keySet());
        var result = proposal.deepCopy();
        int index = 0;
        for (var operation : result.at("/command/payload/operations")) {
            index++;
            String id = operation.path("id").asText();
            if (
                !id.matches("[A-Za-z][A-Za-z0-9_-]{0,39}") ||
                counts.getOrDefault(id, 0) < 2 ||
                referenced.contains(id)
            ) continue;
            String unique = "server_op_" + index;
            int suffix = 0;
            while (!used.add(unique)) unique = "server_op_" + index + "_" + ++suffix;
            ((ObjectNode) operation).put("id", unique);
        }
        return result;
    }

    private static void reference(JsonNode node, Set<String> referenced) {
        if (node.isTextual() && node.asText().startsWith("@")) referenced.add(
            node.asText().substring(1)
        );
    }
}
