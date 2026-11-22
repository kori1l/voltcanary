package com.voltcanary.aiagent.adapter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.aiagent.orchestration.collaboration.PrivateKnowledge;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.Candidate;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.RoutingPolicy;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.Usage;
import com.voltcanary.aiagent.orchestration.contract.SkillRegistry;
import com.voltcanary.aiagent.orchestration.routing.AttemptLedger;
import com.voltcanary.aiagent.orchestration.routing.ModelRouting;
import com.voltcanary.aiagent.trace.AssistantTrace;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

public final class BudgetedEmbedding {

    private BudgetedEmbedding() {}

    public static PrivateKnowledge.Selection searchPrivateKnowledge(
        ObjectMapper mapper,
        PrivateKnowledge knowledge,
        com.voltcanary.aiagent.orchestration.collaboration.CollaborationStore collaboration,
        com.voltcanary.aiagent.orchestration.plan.IntentPlans.Token token,
        long requirementsRevision,
        java.util.Set<UUID> corpusRefs,
        UUID generation,
        String modelIdentity,
        Function<List<String>, List<float[]>> embed,
        AttemptLedger ledger,
        RoutingPolicy policy,
        String stepId,
        UUID parentCall,
        String query,
        int limit
    ) {
        var turn = new AttemptLedger.Turn(
            token.owner(),
            token.conversationId(),
            token.planId(),
            token.requestId()
        );
        return knowledge.select(collaboration, token, requirementsRevision, corpusRefs, scope -> {
            if (!knowledge.authorizeCorpora(scope).equals(scope.corpusIds())) {
                throw new ModelRouting.Stop("KNOWLEDGE_CORPUS_ACCESS_CHANGED");
            }
            return searchPrivateKnowledge(
                mapper,
                knowledge,
                scope,
                generation,
                modelIdentity,
                embed,
                ledger,
                turn,
                policy,
                stepId,
                parentCall,
                query,
                limit
            );
        });
    }

    public static List<PrivateKnowledge.Hit> searchPrivateKnowledge(
        ObjectMapper mapper,
        PrivateKnowledge knowledge,
        PrivateKnowledge.Scope scope,
        UUID generation,
        String modelIdentity,
        Function<List<String>, List<float[]>> embed,
        AttemptLedger ledger,
        AttemptLedger.Turn turn,
        RoutingPolicy policy,
        String stepId,
        UUID parentCall,
        String query,
        int limit
    ) {
        if (
            !scope.accountId().equals(turn.owner().accountId()) ||
            !scope.principalId().equals(turn.owner().userId())
        ) {
            throw new ModelRouting.Stop("KNOWLEDGE_SCOPE_MISMATCH");
        }
        if (query.isBlank() || query.length() > 4096 || limit < 1 || limit > 80) {
            throw new ModelRouting.Stop("INVALID_KNOWLEDGE_SEARCH");
        }
        if (scope.corpusIds().isEmpty()) return List.of();
        String version = "private-knowledge-v1:" + generation;
        String context =
            "private-knowledge:" +
            SkillRegistry.digest(scope.corpusIds().stream().sorted().toList().toString());
        UUID scopedCall = UUID.nameUUIDFromBytes(
            (parentCall + ":" + version + ":" + context).getBytes(StandardCharsets.UTF_8)
        );
        var vectors = exchange(
            mapper,
            modelIdentity,
            version,
            context,
            embed,
            ledger,
            turn,
            policy,
            stepId,
            scopedCall,
            "KNOWLEDGE_QUERY_EMBEDDING",
            List.of(query)
        );
        if (vectors.size() != 1 || vectors.getFirst() == null) throw new ModelRouting.Stop(
            "EMBEDDING_RECEIPT_INVALID"
        );
        return knowledge.searchHybrid(scope, query, generation, vectors.getFirst(), limit);
    }

    static List<float[]> exchange(
        ObjectMapper mapper,
        String identity,
        String version,
        String context,
        Function<List<String>, List<float[]>> embed,
        AttemptLedger ledger,
        AttemptLedger.Turn turn,
        RoutingPolicy policy,
        String stepId,
        UUID parentCall,
        String purpose,
        List<String> texts
    ) {
        String body = mapper.valueToTree(texts).toString();
        String digest = SkillRegistry.digest(identity + ":" + body);
        UUID call = UUID.nameUUIDFromBytes(
            (parentCall + ":" + purpose + ":" + digest).getBytes(StandardCharsets.UTF_8)
        );
        long bytes = body.getBytes(StandardCharsets.UTF_8).length;
        var size = new ModelRouting.Size(bytes + 4096, 0, bytes, "ESTIMATED");
        var candidate = new Candidate(
            "deployment-embedding",
            identity,
            null,
            size.inputTokens(),
            size.inputTokens(),
            0L,
            "EMBEDDING",
            null
        );
        if (policy.unknownCostUpperBoundMicros() == null) throw new ModelRouting.Stop(
            "EMBEDDING_COST_BOUND_REQUIRED"
        );
        var route = new ModelRouting.Route(
            candidate,
            size,
            policy.unknownCostUpperBoundMicros(),
            null,
            "DEPLOYMENT_EMBEDDING"
        );
        var attempt = new AttemptLedger.Attempt(
            call,
            "EMBEDDING",
            stepId,
            digest,
            policy.policyId(),
            policy.policyVersion(),
            version,
            "assistant-v2",
            context,
            route,
            purpose
        );
        var reservation = ledger.reserve(turn, attempt);
        if (!reservation.newlyReserved()) {
            if (!reservation.status().equals("SUCCEEDED")) throw new ModelRouting.Stop(
                "EMBEDDING_REPLAY_UNAVAILABLE"
            );
            try {
                return mapper.readValue(
                    reservation.responseJson(),
                    new TypeReference<List<float[]>>() {}
                );
            } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
                throw new ModelRouting.Stop("EMBEDDING_RECEIPT_INVALID");
            }
        }
        var unknownUsage = new Usage(null, null, null, null, null);
        List<float[]> vectors;
        AssistantTrace.Step trace = null;
        try {
            ledger.remainingTimeMillis(turn);
            trace = AssistantTrace.start(
                "MODEL_CALL",
                Map.of(
                    "callId",
                    call.toString(),
                    "phase",
                    "EMBEDDING",
                    "purpose",
                    purpose,
                    "modelConfigId",
                    identity,
                    "outputMode",
                    "EMBEDDING",
                    "estimatedInputTokens",
                    size.inputTokens(),
                    "reservedOutputTokens",
                    size.outputTokens(),
                    "reservedCostMicros",
                    route.costMicros()
                )
            );
            vectors = embed.apply(texts);
        } catch (RuntimeException failure) {
            if (trace != null) {
                trace.failure("EMBEDDING_OUTCOME_UNKNOWN", Map.of("usage", "UNKNOWN"));
                trace.close();
            }
            ledger.finish(turn, call, trace == null ? "FAILED" : "UNCERTAIN", unknownUsage, null);
            throw failure;
        }
        try (var span = trace) {
            ledger.finish(
                turn,
                call,
                "SUCCEEDED",
                unknownUsage,
                mapper.valueToTree(vectors).toString()
            );
            span.success(Map.of("usage", "UNKNOWN"));
        } catch (ModelRouting.Stop stopped) {
            if ("LATE_RESULT".equals(stopped.getMessage())) ledger.finish(
                turn,
                call,
                "UNCERTAIN",
                unknownUsage,
                null
            );
            throw stopped;
        }
        return vectors;
    }
}
