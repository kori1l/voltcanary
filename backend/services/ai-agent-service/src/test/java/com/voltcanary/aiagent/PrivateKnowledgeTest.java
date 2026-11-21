package com.voltcanary.aiagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.aiagent.adapter.BudgetedEmbedding;
import com.voltcanary.aiagent.orchestration.collaboration.CollaborationStore;
import com.voltcanary.aiagent.orchestration.collaboration.Consultations;
import com.voltcanary.aiagent.orchestration.collaboration.PrivateKnowledge;
import com.voltcanary.aiagent.orchestration.collaboration.Requirements;
import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol.RoutingPolicy;
import com.voltcanary.aiagent.orchestration.plan.IntentPlans;
import com.voltcanary.aiagent.orchestration.routing.AttemptLedger;
import com.voltcanary.aiagent.orchestration.routing.ModelRouting;
import com.voltcanary.aiagent.service.AuthorizedPlanningContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PrivateKnowledgeTest {
    private final UUID corpus = UUID.randomUUID();
    private final PrivateKnowledge.Scope scope = new PrivateKnowledge.Scope(UUID.randomUUID(), UUID.randomUUID(), Set.of(corpus));
    private final PrivateKnowledge.Hit current = hit(corpus);

    @Test
    void enablingKnowledgePreservesPinnedSkillDigestAndExistingCommandContracts() {
        var mapper = new ObjectMapper();
        var validator = new com.voltcanary.aiagent.orchestration.contract.ProtocolValidator(mapper);
        var clock = java.time.Clock.systemUTC();
        var store = mock(CollaborationStore.class);
        var knowledge = mock(PrivateKnowledge.class);
        var disabled = new com.voltcanary.aiagent.orchestration.contract.SkillRegistry(validator,
                com.voltcanary.aiagent.service.PrimarySkillPackages.build(mapper, validator, List.of()));
        var enabled = new com.voltcanary.aiagent.orchestration.contract.SkillRegistry(validator,
                com.voltcanary.aiagent.service.PrimarySkillPackages.build(mapper, validator, List.of(
                        com.voltcanary.aiagent.adapter.PrivateKnowledgeSearchLoader.definition(mapper, clock, store, knowledge),
                        com.voltcanary.aiagent.adapter.PrivateKnowledgeSearchLoader.resolveDefinition(mapper, clock, store, knowledge))));
        var intents = Set.of("CREATE_ALERT_RULE", "CREATE_DEVICE", "CREATE_DASHBOARD");
        var capabilities = Set.<String>of();

        assertThat(enabled.versionDigest(intents, capabilities)).isEqualTo(disabled.versionDigest(intents, capabilities));
        for (var intent : intents) {
            assertThat(enabled.commandSchema(intent, intents, capabilities))
                    .isEqualTo(disabled.commandSchema(intent, intents, capabilities));
            var before = disabled.manifest(intent, intents, capabilities);
            var after = enabled.require(intent, before.skillId(), before.skillVersion(), intents, capabilities);
            assertThat(after.instructions().digest()).isEqualTo(before.instructions().digest());
            if ("CREATE_ALERT_RULE".equals(intent)) {
                assertThat(after.loaderIds()).containsAll(before.loaderIds()).contains("knowledge.search", "knowledge.resolve");
            } else {
                assertThat(after.loaderIds()).isEqualTo(before.loaderIds());
            }
        }
    }

    @Test
    void registeredRuleLoaderReturnsOnlyMetadataAndRejectsUnconfirmedCorpora() {
        var mapper = new ObjectMapper();
        var validator = new com.voltcanary.aiagent.orchestration.contract.ProtocolValidator(mapper);
        var owner = new AuthorizedPlanningContext.Scope(scope.principalId(), scope.accountId());
        var plan = UUID.randomUUID();
        var token = new IntentPlans.Token(owner, UUID.randomUUID(), plan, UUID.randomUUID(), UUID.randomUUID(),
                1, UUID.randomUUID(), UUID.randomUUID(), "temperature", null);
        var slotId = UUID.randomUUID();
        var slot = new Requirements.Slot(slotId, "/knowledge/corpusRefs", "fixture", true,
                Requirements.Status.CONFIRMED, mapper.createArrayNode().add(corpus.toString()),
                Set.of(), Set.of(UUID.randomUUID()), Set.of(), 1, Requirements.Origin.OWNER_EVIDENCE);
        var store = mock(CollaborationStore.class);
        when(store.read(owner, plan)).thenReturn(new CollaborationStore.State(1,
                new Requirements.Snapshot(Requirements.VERSION, plan, 1, Map.of(slotId, slot), null, List.of()),
                new Consultations.Tree(plan, 0, java.time.Instant.MAX, Map.of())));
        var index = mock(PrivateKnowledge.CandidateIndex.class);
        when(index.searchIds(scope, "temperature", 4)).thenReturn(List.of(current));
        var hydrationCalls = new java.util.concurrent.atomic.AtomicInteger();
        var grant = new AtomicReference<>(Set.of(current));
        var access = new PrivateKnowledge.OwnerAccess() {
            @Override
            public Set<PrivateKnowledge.Hit> authorizeCurrent(PrivateKnowledge.Scope s, List<PrivateKnowledge.Hit> hits) {
                return grant.get();
            }

            @Override
            public Set<UUID> authorizeCorpora(PrivateKnowledge.Scope s) {
                return grant.get().isEmpty() ? Set.of() : s.corpusIds();
            }
        };
        var knowledge = new PrivateKnowledge(index, access, (s, hits) -> {
            hydrationCalls.incrementAndGet();
            return List.of(new PrivateKnowledge.Evidence(current, "temperature policy"));
        });
        var definition = com.voltcanary.aiagent.adapter.PrivateKnowledgeSearchLoader
                .definition(mapper, java.time.Clock.systemUTC(), store, knowledge);
        var registry = new com.voltcanary.aiagent.orchestration.contract.SkillRegistry(validator,
                com.voltcanary.aiagent.service.PrimarySkillPackages.build(mapper, validator, List.of(definition)));
        var authorized = Set.of("CREATE_ALERT_RULE", "CREATE_DEVICE");
        var argumentsSchema = registry.loaderSchema("CREATE_ALERT_RULE", definition.id(),
                definition.argumentsSchemaId(), authorized, Set.of());
        assertThat(registry.manifest("CREATE_DEVICE", authorized, Set.of()).loaderIds()).doesNotContain(definition.id());
        var args = mapper.createObjectNode().put("query", "temperature").put("purpose", "RULE_POLICY_EVIDENCE")
                .put("requirementsRevision", 1).put("limit", 4);
        args.putArray("corpusRefs").add(corpus.toString());
        validator.validateRegistered(argumentsSchema, args);
        var input = new IntentPlans.ModelInput(token, null, Map.of(), null);
        var session = new com.voltcanary.aiagent.orchestration.plan.OrchestrationPorts.Session(null, owner, null);
        var page = definition.loader().load(input, session, args, null);
        var result = page.data();
        validator.validateRegistered(definition.loader().resultSchema(), result);
        assertThat(result.at("/hits/0")).isEqualTo(mapper.valueToTree(current));
        assertThat(hydrationCalls.get()).isZero();
        var resolve = com.voltcanary.aiagent.adapter.PrivateKnowledgeSearchLoader
                .resolveDefinition(mapper, java.time.Clock.systemUTC(), store, knowledge);
        var resolveArgs = mapper.createObjectNode().put("snapshotId", page.snapshotId());
        resolveArgs.putArray("chunkIds").add(current.chunkId().toString());
        var previous = mapper.createArrayNode();
        previous.addObject().put("loaderId", definition.id()).put("snapshotId", page.snapshotId()).set("data", result);
        assertThatThrownBy(() -> resolve.loader().load(input, session, resolveArgs, null))
                .hasMessageContaining("KNOWLEDGE_SEARCH_REQUIRED");
        var resolved = resolve.loader().load(input, session, resolveArgs, null, previous).data();
        validator.validateRegistered(resolve.loader().resultSchema(), resolved);
        assertThat(resolved.at("/evidence/0/text").asText()).isEqualTo("temperature policy");
        var loading = new com.voltcanary.aiagent.orchestration.context.ContextLoading(registry, validator, null,
                List.of(definition.loader(), resolve.loader()), java.time.Clock.systemUTC());
        var dispatch = mapper.createArrayNode();
        dispatch.addObject().put("loaderId", resolve.id()).set("data", resolved);
        loading.verifyForDispatch(input, session, dispatch);
        grant.set(Set.of());
        assertThatThrownBy(() -> loading.verifyForDispatch(input, session, dispatch))
                .hasMessageContaining("KNOWLEDGE_EVIDENCE_ACCESS_CHANGED");
        resolveArgs.putArray("chunkIds").add(UUID.randomUUID().toString());
        assertThatThrownBy(() -> resolve.loader().load(input, session, resolveArgs, null, previous))
                .hasMessageContaining("KNOWLEDGE_SELECTION_MISMATCH");
        assertThat(hydrationCalls.get()).isEqualTo(1);

        args.putArray("corpusRefs").add(UUID.randomUUID().toString());
        assertThatThrownBy(() -> definition.loader().load(input, session, args, null))
                .hasMessageContaining("KNOWLEDGE_CORPUS_SCOPE_DENIED");
        org.mockito.Mockito.verify(index).searchIds(scope, "temperature", 4);

        args.putArray("corpusRefs").add(corpus.toString());
        grant.set(Set.of(current));
        var generation = UUID.randomUUID();
        var embedder = mock(com.voltcanary.aiagent.port.CapabilityEmbeddings.class);
        when(embedder.identity()).thenReturn("fixture");
        var ledger = mock(AttemptLedger.class);
        var turn = new AttemptLedger.Turn(owner, token.conversationId(), plan, token.requestId());
        var policy = new RoutingPolicy("assistant-v2", "routing.policy", "fixture", "1", "PINNED",
                null, List.of(), null, null, "RESERVED_UPPER_BOUND", 100L, null, null);
        var budget = new com.voltcanary.aiagent.orchestration.context.ContextSources.ModelBudget(
                ledger, policy, "rule", UUID.randomUUID());
        var hybrid = com.voltcanary.aiagent.adapter.PrivateKnowledgeSearchLoader
                .definition(mapper, java.time.Clock.systemUTC(), store, knowledge, generation, embedder);
        when(ledger.reserve(eq(turn), any())).thenReturn(new AttemptLedger.Receipt(true, "STARTED", null));
        var vector = new float[] {1, 0};
        when(embedder.embed(List.of("temperature"))).thenReturn(List.of(vector));
        when(index.searchIds(scope, "temperature", 40)).thenReturn(List.of());
        when(index.searchVectorIds(scope, generation, vector, 40)).thenReturn(List.of(current));

        var hybridPage = hybrid.loader().load(input, session, args, null, null, budget);
        validator.validateRegistered(hybrid.loader().resultSchema(), hybridPage.data());
        assertThat(hybridPage.data().path("mode").asText()).isEqualTo("HYBRID");
        assertThat(hybridPage.data().at("/hits/0")).isEqualTo(mapper.valueToTree(current));
        assertThat(hydrationCalls.get()).isEqualTo(1);
        var ordered = org.mockito.Mockito.inOrder(ledger, embedder, index);
        ordered.verify(ledger).reserve(eq(turn), any());
        ordered.verify(embedder).embed(List.of("temperature"));
        ordered.verify(index).searchVectorIds(scope, generation, vector, 40);
        org.mockito.Mockito.verify(index).searchIds(scope, "temperature", 40);
        grant.set(Set.of());
        org.mockito.Mockito.clearInvocations(ledger, embedder);
        assertThatThrownBy(() -> hybrid.loader().load(input, session, args, null, null, budget))
                .hasMessage("KNOWLEDGE_CORPUS_ACCESS_CHANGED");
        verifyNoInteractions(ledger, embedder);
        org.mockito.Mockito.verifyNoMoreInteractions(index);
    }

    @Test
    void privateTextIsWithheldWhenRequirementsChangeDuringHydration() {
        var owner = new AuthorizedPlanningContext.Scope(scope.principalId(), scope.accountId());
        var plan = UUID.randomUUID();
        var token = new IntentPlans.Token(owner, UUID.randomUUID(), plan, UUID.randomUUID(), UUID.randomUUID(),
                1, UUID.randomUUID(), UUID.randomUUID(), "temperature", null);
        var slotId = UUID.randomUUID();
        var slot = new Requirements.Slot(slotId, "/knowledge/corpusRefs", "fixture", true,
                Requirements.Status.CONFIRMED, new ObjectMapper().createArrayNode().add(corpus.toString()),
                Set.of(), Set.of(UUID.randomUUID()), Set.of(), 1, Requirements.Origin.OWNER_EVIDENCE);
        var store = mock(CollaborationStore.class);
        var tree = new Consultations.Tree(plan, 0, java.time.Instant.MAX, Map.of());
        var before = new Requirements.Snapshot(Requirements.VERSION, plan, 1, Map.of(slotId, slot), null, List.of());
        var after = new Requirements.Snapshot(Requirements.VERSION, plan, 2, Map.of(), null, List.of());
        var state = new AtomicReference<>(new CollaborationStore.State(1, before, tree));
        when(store.read(owner, plan)).thenAnswer(invocation -> state.get());
        var knowledge = new PrivateKnowledge((s, query, limit) -> List.of(), (s, hits) -> Set.of(current),
                (s, hits) -> {
                    state.set(new CollaborationStore.State(2, after, tree));
                    return List.of(new PrivateKnowledge.Evidence(current, "private text"));
                });
        var selection = knowledge.select(store, token, 1, Set.of(corpus), authorized -> List.of(current));
        assertThat(selection.hits()).containsExactly(current);
        assertThatThrownBy(() -> knowledge.resolve(store, token, selection, List.of(hit(corpus))))
                .hasMessageContaining("KNOWLEDGE_SELECTION_MISMATCH");
        var anotherPlan = new IntentPlans.Token(owner, token.conversationId(), UUID.randomUUID(), token.requestId(),
                token.messageId(), 1, token.primaryCallId(), token.replanCallId(), token.message(), null);
        assertThatThrownBy(() -> knowledge.resolve(store, anotherPlan, selection, List.of(current)))
                .hasMessageContaining("KNOWLEDGE_SELECTION_MISMATCH");
        assertThatThrownBy(() -> knowledge.resolve(store, token, selection, List.of(current)))
                .hasMessageContaining("KNOWLEDGE_REQUIREMENTS_CHANGED");
        assertThatThrownBy(() -> knowledge.resolve(store, token, 2, Set.of(corpus), List.of(current)))
                .hasMessageContaining("KNOWLEDGE_CORPUS_SCOPE_DENIED");
    }

    @Test
    void privateQueryChecksOwnershipAndBudgetBeforeEmbeddingOrSearching() {
        var ledger = mock(AttemptLedger.class);
        var knowledge = mock(PrivateKnowledge.class);
        var mapper = new ObjectMapper();
        var turn = new AttemptLedger.Turn(new AuthorizedPlanningContext.Scope(scope.principalId(), scope.accountId()),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        var policy = new RoutingPolicy("assistant-v2", "routing.policy", "fixture", "1", "PINNED",
                null, List.of(), null, null, "RESERVED_UPPER_BOUND", 100L, null, null);
        var generation = UUID.randomUUID();
        var call = UUID.randomUUID();
        java.util.function.Function<List<String>, List<float[]>> forbidden =
                texts -> { throw new AssertionError("Embedding provider must not be called"); };
        var wrongScope = new PrivateKnowledge.Scope(scope.accountId(), UUID.randomUUID(), Set.of(corpus));
        assertThatThrownBy(() -> BudgetedEmbedding.searchPrivateKnowledge(mapper, knowledge, wrongScope,
                generation, "fixture", forbidden, ledger, turn, policy, "step", call, "temperature", 4))
                .hasMessage("KNOWLEDGE_SCOPE_MISMATCH");
        verifyNoInteractions(ledger, knowledge);

        when(ledger.reserve(eq(turn), any())).thenThrow(new ModelRouting.Stop("CALL_BUDGET_EXHAUSTED"));
        assertThatThrownBy(() -> BudgetedEmbedding.searchPrivateKnowledge(mapper, knowledge, scope,
                generation, "fixture", forbidden, ledger, turn, policy, "step", call, "temperature", 4))
                .hasMessage("CALL_BUDGET_EXHAUSTED");
        verifyNoInteractions(knowledge);
    }

    @Test
    void privateQueryReusesCompletedEmbeddingButSearchesAgain() {
        var ledger = mock(AttemptLedger.class);
        var knowledge = mock(PrivateKnowledge.class);
        var mapper = new ObjectMapper();
        var turn = new AttemptLedger.Turn(new AuthorizedPlanningContext.Scope(scope.principalId(), scope.accountId()),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        var policy = new RoutingPolicy("assistant-v2", "routing.policy", "fixture", "1", "PINNED",
                null, List.of(), null, null, "RESERVED_UPPER_BOUND", 100L, null, null);
        var generation = UUID.randomUUID();
        var call = UUID.randomUUID();
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        when(ledger.reserve(eq(turn), any())).thenReturn(new AttemptLedger.Receipt(true, "STARTED", null),
                new AttemptLedger.Receipt(false, "SUCCEEDED", "[[1.0]]"));
        when(knowledge.searchHybrid(eq(scope), eq("temperature"), eq(generation), any(), eq(4)))
                .thenReturn(List.of(current), List.of());

        for (var expected : List.of(List.of(current), List.<PrivateKnowledge.Hit>of())) {
            assertThat(BudgetedEmbedding.searchPrivateKnowledge(mapper, knowledge, scope, generation, "fixture",
                    texts -> { calls.incrementAndGet(); return List.of(new float[] {1}); },
                    ledger, turn, policy, "step", call, "temperature", 4)).isEqualTo(expected);
        }
        assertThat(calls.get()).isEqualTo(1);
        var attempts = org.mockito.ArgumentCaptor.forClass(AttemptLedger.Attempt.class);
        org.mockito.Mockito.verify(ledger, org.mockito.Mockito.times(2)).reserve(eq(turn), attempts.capture());
        assertThat(attempts.getAllValues()).extracting(AttemptLedger.Attempt::callId)
                .containsOnly(attempts.getValue().callId());
        assertThat(attempts.getValue().purpose()).isEqualTo("KNOWLEDGE_QUERY_EMBEDDING");
    }

    @Test
    void checksScopeBeforeOwnerAccessAndReauthorizesBeforeReadingText() {
        var grant = new AtomicReference<>(Set.of(current));
        var knowledge = new PrivateKnowledge((ignored, query, limit) -> List.of(hit(UUID.randomUUID()), current),
                (actual, candidates) -> {
                    assertThat(actual).isEqualTo(scope);
                    assertThat(candidates).containsExactly(current);
                    return grant.get();
                }, (ignored, authorized) -> {
                    throw new AssertionError("Revoked text must never be read");
                });

        var candidates = knowledge.search(scope, "temperature", 2);
        assertThat(candidates).containsExactly(current);
        grant.set(Set.of());
        assertThat(knowledge.resolve(scope, candidates)).isEmpty();
    }

    @Test
    void dropsEvidenceRevokedWhileTextWasBeingRead() {
        var grant = new AtomicReference<>(Set.of(current));
        var knowledge = new PrivateKnowledge((ignored, query, limit) -> List.of(current),
                (ignored, candidates) -> grant.get(),
                (ignored, authorized) -> {
                    assertThat(authorized).containsExactly(current);
                    grant.set(Set.of());
                    return List.of(new PrivateKnowledge.Evidence(current, "private text"));
                });

        assertThat(knowledge.resolve(scope, List.of(current))).isEmpty();
    }

    @Test
    void rejectsTextFromARevisionOutsideTheAuthorizedSet() {
        var changed = new PrivateKnowledge.Hit(corpus, current.sourceId(), "v2", current.chunkId());
        var knowledge = new PrivateKnowledge((ignored, query, limit) -> List.of(current),
                (ignored, candidates) -> Set.of(current),
                (ignored, authorized) -> List.of(new PrivateKnowledge.Evidence(changed, "private text")));

        assertThatThrownBy(() -> knowledge.resolve(scope, List.of(current)))
                .hasMessageContaining("KNOWLEDGE_EVIDENCE_MISMATCH");
    }

    @Test
    void hybridRanksAuthorizedCandidatesAcrossBothChannelsWithoutHydratingText() {
        var overlap = hit(corpus);
        var denied = hit(corpus);
        var vectorOnly = hit(corpus);
        var generation = UUID.randomUUID();
        var index = new PrivateKnowledge.CandidateIndex() {
            @Override
            public List<PrivateKnowledge.Hit> searchIds(PrivateKnowledge.Scope actual, String query, int limit) {
                assertThat(limit).isEqualTo(40);
                return List.of(denied, current, overlap, overlap);
            }

            @Override
            public List<PrivateKnowledge.Hit> searchVectorIds(PrivateKnowledge.Scope actual, UUID pinned,
                    float[] embedding, int limit) {
                assertThat(pinned).isEqualTo(generation);
                assertThat(limit).isEqualTo(40);
                return List.of(vectorOnly, overlap);
            }
        };
        var knowledge = new PrivateKnowledge(index, (actual, candidates) -> {
            assertThat(candidates).containsExactly(denied, current, overlap, vectorOnly);
            return Set.of(current, overlap, vectorOnly);
        }, (actual, candidates) -> { throw new AssertionError("Search must return IDs only"); });

        assertThat(knowledge.searchHybrid(scope, "temperature", generation, new float[] {1}, 3))
                .containsExactly(overlap, current, vectorOnly);
        assertThat(knowledge.searchHybrid(scope, current.sourceId().toString(), generation, new float[] {1}, 2))
                .containsExactly(current, overlap);
    }

    private static PrivateKnowledge.Hit hit(UUID corpusId) {
        return new PrivateKnowledge.Hit(corpusId, UUID.randomUUID(), "v1", UUID.randomUUID());
    }
}
