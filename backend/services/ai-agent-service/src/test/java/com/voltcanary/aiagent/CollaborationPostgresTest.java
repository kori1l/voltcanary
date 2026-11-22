package com.voltcanary.aiagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.voltcanary.aiagent.orchestration.contract.AssistantProtocol;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@org.junit.jupiter.api.Tag("docker")
class CollaborationPostgresTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
        org.testcontainers.utility.DockerImageName.parse(
            "pgvector/pgvector@sha256:484e838076e04939d27d3e176e91c86eea96236126023be8d3059761122e579f"
        ).asCompatibleSubstituteFor("postgres")
    );

    @org.junit.jupiter.api.BeforeAll
    static void installVectorExtension() {
        var db = new DriverManagerDataSource(
            postgres.getJdbcUrl(),
            postgres.getUsername(),
            postgres.getPassword()
        );
        new JdbcTemplate(db).execute("CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public");
    }

    @Test
    void privateKnowledgeChecksExactVersionsAndRevocationInPostgres() {
        var db = new DriverManagerDataSource(
            postgres.getJdbcUrl(),
            postgres.getUsername(),
            postgres.getPassword()
        );
        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
            new org.springframework.core.io.ClassPathResource(
                "db/migration/V012__assistant_private_knowledge.sql"
            ),
            new org.springframework.core.io.ClassPathResource(
                "db/migration/V013__assistant_knowledge_search_indexes.sql"
            ),
            new org.springframework.core.io.ClassPathResource(
                "db/migration/V014__assistant_knowledge_row_security.sql"
            ),
            new org.springframework.core.io.ClassPathResource(
                "db/knowledge/V001__private_knowledge_vectors.sql"
            )
        ).execute(db);
        JdbcPrivateKnowledgeTest.verify(new JdbcTemplate(db));
    }

    private CollaborationStoreTest.Fixture fixture() throws Exception {
        return fixture("{}");
    }

    private CollaborationStoreTest.Fixture fixture(String requestJson) throws Exception {
        String schema = "collaboration_" + UUID.randomUUID().toString().replace("-", "");
        var root = new DriverManagerDataSource(
            postgres.getJdbcUrl(),
            postgres.getUsername(),
            postgres.getPassword()
        );
        new JdbcTemplate(root).execute("create schema " + schema);
        String separator = postgres.getJdbcUrl().contains("?") ? "&" : "?";
        return CollaborationStoreTest.fixture(
            new IntentScenarioSupport(
                new DriverManagerDataSource(
                    postgres.getJdbcUrl() + separator + "currentSchema=" + schema + ",public",
                    postgres.getUsername(),
                    postgres.getPassword()
                )
            ),
            requestJson
        );
    }

    @Test
    void selectedKnowledgeScopeIsJournaledOnceAndReauthorizedOnReplay() throws Exception {
        var corpus = UUID.randomUUID();
        var submitted =
            new com.fasterxml.jackson.databind.ObjectMapper().<com.fasterxml.jackson.databind.node.ObjectNode>valueToTree(
                new AssistantProtocol.TurnSubmit(
                    "assistant-v2",
                    "turn.submit",
                    UUID.randomUUID().toString(),
                    0L,
                    "Describe requirements",
                    null,
                    List.of(
                        new AssistantProtocol.TurnSubmitSelectionItem(
                            "knowledgeCorpusRef",
                            corpus.toString()
                        )
                    ),
                    new AssistantProtocol.RoutingPreference("PINNED", "policy", "fixture-model"),
                    null,
                    null
                )
            );
        var fixture = fixture(submitted.toString());
        var f = fixture.f();
        var token = fixture.turn();
        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
            new org.springframework.core.io.ClassPathResource(
                "db/migration/V012__assistant_private_knowledge.sql"
            ),
            new org.springframework.core.io.ClassPathResource(
                "db/migration/V014__assistant_knowledge_row_security.sql"
            ),
            new org.springframework.core.io.ClassPathResource(
                "db/knowledge/V001__private_knowledge_vectors.sql"
            )
        ).execute(f.jdbc.getDataSource());
        var source = UUID.randomUUID();
        var chunk = UUID.randomUUID();
        var generation = UUID.randomUUID();
        f.jdbc.update(
            "INSERT INTO assistant_knowledge_source VALUES (?, ?, ?, 'v1', true)",
            token.owner().accountId(),
            corpus,
            source
        );
        f.jdbc.update(
            "INSERT INTO assistant_knowledge_reader VALUES (?, ?, ?, ?)",
            token.owner().accountId(),
            corpus,
            source,
            token.owner().userId()
        );
        String policyText = "synthetic temperature policy: threshold 80";
        f.jdbc.update(
            "INSERT INTO assistant_knowledge_chunk VALUES (?, ?, ?, 'v1', ?, ?)",
            token.owner().accountId(),
            corpus,
            source,
            chunk,
            policyText
        );
        f.jdbc.update(
            "INSERT INTO assistant_knowledge_vector VALUES (?, ?, ?, 'v1', ?, ?, '[1,0]')",
            token.owner().accountId(),
            corpus,
            source,
            chunk,
            generation
        );
        var adapter = new com.voltcanary.aiagent.adapter.JdbcPrivateKnowledge(
            new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(f.jdbc)
        );
        var knowledge = new com.voltcanary.aiagent.orchestration.collaboration.PrivateKnowledge(
            adapter,
            adapter,
            adapter
        );
        var embeddingCalls = new java.util.concurrent.atomic.AtomicInteger();
        var embeddings = new com.voltcanary.aiagent.port.CapabilityEmbeddings() {
            public String identity() {
                return "fixture-embedding";
            }

            public List<float[]> embed(List<String> texts) {
                assertThat(texts).containsExactly("thermal threshold");
                embeddingCalls.incrementAndGet();
                return List.of(new float[] { 1, 0 });
            }
        };
        var searchDefinition =
            com.voltcanary.aiagent.adapter.PrivateKnowledgeSearchLoader.definition(
                f.mapper,
                f.clock,
                fixture.store(),
                knowledge,
                generation,
                embeddings
            );
        var loader = searchDefinition.loader();
        var input = new com.voltcanary.aiagent.orchestration.plan.IntentPlans.ModelInput(
            token,
            null,
            java.util.Map.of(),
            null
        );

        var initial = loader.initial(input, f.session, () -> submitted);
        var contextLoading = new com.voltcanary.aiagent.orchestration.context.ContextLoading(
            f.registry,
            f.validator,
            null,
            List.of(loader),
            f.clock
        );
        var projectedScope = contextLoading.initial(loader.id(), input, f.session, () -> submitted);
        assertThat(projectedScope.loaderId()).isEqualTo("knowledge.search.scope");
        assertThat(projectedScope.data()).isEqualTo(initial.data());
        assertThat(initial.data().path("requirementsRevision").asLong()).isEqualTo(1);
        loader.verifyInitialForDispatch(input, f.session, initial.data());
        assertThat(initial.data().at("/corpusRefs/0").asText()).isEqualTo(corpus.toString());
        assertThat(loader.initial(input, f.session, () -> submitted).data()).isEqualTo(
            initial.data()
        );
        assertThat(loader.initial(input, f.session, f.mapper::createObjectNode).data()).isEqualTo(
            initial.data()
        );
        var saved = fixture.store().read(token.owner(), token.planId()).requirements();
        assertThat(saved.events()).hasSize(1);
        assertThat(saved.slots().values())
            .singleElement()
            .satisfies(slot -> {
                assertThat(slot.origin()).isEqualTo(
                    com.voltcanary.aiagent.orchestration.collaboration.Requirements.Origin.OWNER_EVIDENCE
                );
                assertThat(slot.sourceMessages()).containsExactly(token.messageId());
            });

        var searchArgs = initial
            .data()
            .<com.fasterxml.jackson.databind.node.ObjectNode>deepCopy()
            .put("query", "thermal threshold")
            .put("purpose", "RULE_POLICY_EVIDENCE")
            .put("limit", 4);
        var ledger = f.ledger();
        var turn = new com.voltcanary.aiagent.orchestration.routing.AttemptLedger.Turn(
            token.owner(),
            token.conversationId(),
            token.planId(),
            token.requestId()
        );
        var limits = new AssistantProtocol.Limits(6L, 700_000L, 72_000L, 240_000L, 600_000L, "USD");
        var policy = new AssistantProtocol.RoutingPolicy(
            "assistant-v2",
            "routing.policy",
            "policy",
            "1",
            "PINNED",
            "fixture-model",
            List.of(
                new AssistantProtocol.RoutingPolicyProfilesItem(
                    "primary",
                    List.of("CREATE_ALERT_RULE"),
                    List.of("STEP"),
                    List.of(
                        new AssistantProtocol.Candidate(
                            "fixture-provider",
                            "fixture-model",
                            null,
                            128_000L,
                            112_000L,
                            12_000L,
                            "JSON_TEXT",
                            null
                        )
                    ),
                    false
                )
            ),
            limits,
            limits,
            "RESERVED_UPPER_BOUND",
            100_000L,
            java.math.BigDecimal.ONE,
            null
        );
        ledger.open(turn, policy);
        var budget = new com.voltcanary.aiagent.orchestration.context.ContextSources.ModelBudget(
            ledger,
            policy,
            "rule",
            UUID.randomUUID()
        );
        var search = loader.load(input, f.session, searchArgs, null, null, budget);
        f.validator.validateRegistered(loader.resultSchema(), search.data());
        assertThat(search.data().path("mode").asText()).isEqualTo("HYBRID");
        assertThat(loader.load(input, f.session, searchArgs, null, null, budget).data()).isEqualTo(
            search.data()
        );
        assertThat(embeddingCalls.get()).isEqualTo(1);
        assertThat(
            f.jdbc.queryForList(
                "SELECT status FROM ai_model_budget_attempts WHERE plan_id = ?",
                String.class,
                token.planId()
            )
        ).containsExactly("SUCCEEDED");
        assertThat(search.data().at("/hits/0/chunkId").asText()).isEqualTo(chunk.toString());
        assertThat(search.data().toString()).doesNotContain("synthetic temperature policy");
        loader.verifyForDispatch(input, f.session, search.data());
        var previous = f.mapper.createArrayNode();
        previous
            .addObject()
            .put("loaderId", loader.id())
            .put("snapshotId", search.snapshotId())
            .set("data", search.data());
        var resolveDefinition =
            com.voltcanary.aiagent.adapter.PrivateKnowledgeSearchLoader.resolveDefinition(
                f.mapper,
                f.clock,
                fixture.store(),
                knowledge
            );
        var resolve = resolveDefinition.loader();
        var resolveArgs = f.mapper.createObjectNode().put("snapshotId", search.snapshotId());
        resolveArgs.putArray("chunkIds").add(chunk.toString());
        var evidence = resolve.load(input, f.session, resolveArgs, null, previous).data();
        f.validator.validateRegistered(resolve.resultSchema(), evidence);
        assertThat(evidence.at("/evidence/0/text").asText()).isEqualTo(policyText);
        resolve.verifyForDispatch(input, f.session, evidence);

        var completed = verifyKnowledgeGraph(
            fixture,
            searchDefinition,
            resolveDefinition,
            chunk,
            policyText,
            policy,
            ledger
        );

        f.jdbc.update(
            "DELETE FROM assistant_knowledge_reader WHERE account_id = ?",
            token.owner().accountId()
        );
        assertThatThrownBy(() ->
            loader.verifyInitialForDispatch(input, f.session, initial.data())
        ).hasMessageContaining("KNOWLEDGE_CORPUS_SCOPE_DENIED");
        assertThatThrownBy(() ->
            loader.verifyForDispatch(input, f.session, search.data())
        ).hasMessageContaining("KNOWLEDGE_EVIDENCE_ACCESS_CHANGED");
        assertThatThrownBy(() ->
            loader.load(input, f.session, searchArgs, null, null, budget)
        ).hasMessage("KNOWLEDGE_CORPUS_ACCESS_CHANGED");
        assertThat(embeddingCalls.get()).isEqualTo(2);
        assertThatThrownBy(() ->
            resolve.verifyForDispatch(input, f.session, evidence)
        ).hasMessageContaining("KNOWLEDGE_EVIDENCE_ACCESS_CHANGED");
        assertThat(
            resolve.load(input, f.session, resolveArgs, null, previous).data().path("evidence")
        ).isEmpty();
        assertThatThrownBy(() ->
            loader.initial(input, f.session, () -> submitted)
        ).hasMessageContaining("KNOWLEDGE_CORPUS_SCOPE_DENIED");
        assertThat(fixture.store().read(token.owner(), token.planId()).requirements()).isEqualTo(
            saved
        );
        var clear = f.mapper.createObjectNode();
        clear
            .putArray("selection")
            .addObject()
            .put("field", "knowledgeCorpusScope")
            .put("ref", "CLEAR");
        var next = f.store.begin(
            token.owner(),
            completed,
            UUID.randomUUID(),
            completed.revision(),
            "TURN",
            "{}",
            "Clear the selected sources",
            false
        );
        var clearInput = new com.voltcanary.aiagent.orchestration.plan.IntentPlans.ModelInput(
            next.token(),
            next.state(),
            java.util.Map.of(),
            null
        );
        assertThat(loader.initial(clearInput, f.session, () -> clear)).isNull();
        var cleared = fixture.store().read(token.owner(), token.planId()).requirements();
        assertThat(cleared.revision()).isEqualTo(saved.revision() + 1);
        assertThat(cleared.slots().values())
            .singleElement()
            .satisfies(slot -> assertThat(slot.value().isEmpty()).isTrue());
        assertThat(loader.initial(clearInput, f.session, f.mapper::createObjectNode)).isNull();
    }

    private static com.voltcanary.aiagent.orchestration.plan.IntentPlans.State verifyKnowledgeGraph(
        CollaborationStoreTest.Fixture fixture,
        com.voltcanary.aiagent.adapter.ScopedDomainContextLoaders.Definition searchDefinition,
        com.voltcanary.aiagent.adapter.ScopedDomainContextLoaders.Definition resolveDefinition,
        UUID chunk,
        String policyText,
        AssistantProtocol.RoutingPolicy policy,
        com.voltcanary.aiagent.orchestration.routing.AttemptLedger ledger
    ) {
        var f = fixture.f();
        var token = fixture.turn();
        var store = f.store;
        var root = store.read(token.owner(), token.planId());
        var search = searchDefinition.loader();
        var resolve = resolveDefinition.loader();
        var definitions = com.voltcanary.aiagent.service.PrimarySkillPackages.build(
            f.mapper,
            f.validator,
            List.of(searchDefinition, resolveDefinition)
        );
        var registry = new com.voltcanary.aiagent.orchestration.contract.SkillRegistry(
            f.validator,
            definitions
        );
        var skills = new com.voltcanary.aiagent.orchestration.plan.IntentOrchestration.Skills(
            java.util.Set.of("CREATE_ALERT_RULE"),
            java.util.Set.of()
        );
        var manifest = registry.manifest(
            "CREATE_ALERT_RULE",
            skills.authorized(),
            skills.capabilities()
        );
        var step = new AssistantProtocol.StepState(
            "rule",
            "CREATE_ALERT_RULE",
            "1",
            "1",
            0L,
            List.of(),
            List.of(),
            List.of("goal"),
            "PREPARING",
            null,
            null,
            null
        );
        var proposed = root.revise(
            root.revision(),
            "ACTIVE",
            List.of(step),
            List.of(
                new AssistantProtocol.Goal(
                    "goal",
                    token.messageId().toString(),
                    "Prepare a rule",
                    "PENDING",
                    null
                )
            ),
            List.of(),
            null,
            new AssistantProtocol.Focus(null, null, null, null, "NONE"),
            java.util.Map.of(
                "rule",
                new com.voltcanary.aiagent.orchestration.plan.IntentPlans.Skill(
                    manifest.skillId(),
                    manifest.skillVersion(),
                    manifest.instructions().digest()
                )
            ),
            java.util.Map.of(),
            java.util.Map.of()
        );
        var persisted = store.commit(token, proposed, null, false);
        var state = store.read(token.owner(), token.planId());
        assertThat(state).isEqualTo(persisted);
        assertThat(state.revision()).isEqualTo(token.revision() + 1);
        var preparedTurn = new com.voltcanary.aiagent.orchestration.plan.IntentPlans.Token(
            token.owner(),
            token.conversationId(),
            token.planId(),
            token.requestId(),
            token.messageId(),
            state.revision(),
            token.primaryCallId(),
            token.replanCallId(),
            token.message(),
            token.answeredQuestion()
        );
        var input = new com.voltcanary.aiagent.orchestration.plan.IntentPlans.ModelInput(
            preparedTurn,
            state,
            java.util.Map.of(),
            new com.voltcanary.aiagent.orchestration.plan.IntentPlans.SourceMessage(
                token.messageId(),
                token.message()
            )
        );
        var history = new com.voltcanary.aiagent.adapter.JdbcContextHistory(f.jdbc, f.clock);
        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
            new org.springframework.core.io.ClassPathResource(
                "db/migration/V010__assistant_primary_bindings.sql"
            )
        ).execute(f.jdbc.getDataSource());
        org.mockito.Mockito.when(
            f.contexts.scope(f.session.user(), f.session.authorization())
        ).thenReturn(token.owner());
        org.mockito.Mockito.when(
            f.contexts.prepare(
                org.mockito.ArgumentMatchers.eq(f.session.user()),
                org.mockito.ArgumentMatchers.eq(f.session.authorization()),
                org.mockito.ArgumentMatchers.eq(token.message()),
                org.mockito.ArgumentMatchers.anyMap()
            )
        ).thenReturn(
            new com.voltcanary.aiagent.service.AuthorizedPlanningContext.Prepared(
                token.owner(),
                java.util.Map.of(),
                List.of()
            )
        );
        var journal = new com.voltcanary.aiagent.adapter.PrimaryOrchestrationJournal(
            f.jdbc,
            store,
            f.mapper
        );
        var domain = com.voltcanary.aiagent.config.PrimaryAssistantConfiguration.primaryDomain(
            f.mapper,
            f.validator,
            store,
            f.reviews,
            f.contexts,
            null,
            new com.voltcanary.aiagent.config.AiAgentProperties(),
            com.voltcanary.common.web.JacksonRestClients.builder(),
            journal,
            f.jdbc
        );
        domain.read(input, f.session);
        var loading = new com.voltcanary.aiagent.orchestration.context.ContextLoading(
            registry,
            f.validator,
            null,
            List.of(
                search,
                resolve,
                new com.voltcanary.aiagent.orchestration.context.ConversationHistoryLoader(
                    history,
                    f.mapper,
                    f.validator,
                    f.clock
                ),
                new com.voltcanary.aiagent.orchestration.context.ContextSources.Loader() {
                    public String id() {
                        return "workflow.context";
                    }

                    public com.fasterxml.jackson.databind.JsonNode resultSchema() {
                        return f.mapper.createObjectNode().put("type", "object");
                    }

                    public com.voltcanary.aiagent.orchestration.context.ContextSources.Page load(
                        com.voltcanary.aiagent.orchestration.plan.IntentPlans.ModelInput i,
                        com.voltcanary.aiagent.orchestration.plan.OrchestrationPorts.Session s,
                        com.fasterxml.jackson.databind.JsonNode a,
                        com.voltcanary.aiagent.orchestration.context.ContextSources.Position p
                    ) {
                        throw new AssertionError("Unexpected loader");
                    }
                }
            ),
            f.clock
        );
        var requests = new com.voltcanary.aiagent.orchestration.context.BoundedSkillRequests(
            f.mapper,
            f.validator,
            registry,
            store,
            history,
            loading,
            (i, s) -> skills,
            f.receipts,
            (i, s) -> null,
            (i, s) -> null,
            f.clock,
            200_000,
            ledger,
            policy
        );
        var count = new java.util.concurrent.atomic.AtomicInteger();
        var adapter =
            new com.voltcanary.aiagent.orchestration.routing.RoutedModelCalls.WireAdapter() {
                public String serialize(
                    com.fasterxml.jackson.databind.JsonNode request,
                    com.fasterxml.jackson.databind.JsonNode schema,
                    String mode
                ) {
                    return request.toString();
                }

                public com.voltcanary.aiagent.orchestration.routing.BudgetedModelCalls.Reply send(
                    String body,
                    com.voltcanary.aiagent.orchestration.routing.ModelRouting.Route route,
                    long timeout
                ) throws Exception {
                    var request = f.mapper.readTree(body);
                    var result = f.mapper
                        .createObjectNode()
                        .put("version", "assistant-v2")
                        .put("kind", "step.response");
                    switch (count.incrementAndGet()) {
                        case 1 -> {
                            var scope = request.path("loadResults").get(0);
                            assertThat(scope.path("loaderId").asText()).isEqualTo(
                                "knowledge.search.scope"
                            );
                            var args = scope
                                .path("data")
                                .<com.fasterxml.jackson.databind.node.ObjectNode>deepCopy()
                                .put("query", "thermal threshold")
                                .put("purpose", "RULE_POLICY_EVIDENCE")
                                .put("limit", 4);
                            result
                                .put("outcome", "NEED_CONTEXT")
                                .put("explanation", "Search for policy evidence")
                                .putArray("requests")
                                .addObject()
                                .put("loaderId", search.id())
                                .put("argumentsSchemaId", searchDefinition.argumentsSchemaId())
                                .putNull("cursor")
                                .set("arguments", args);
                        }
                        case 2 -> {
                            var found = request.path("loadResults").get(1);
                            assertThat(found.at("/data/hits/0/chunkId").asText()).isEqualTo(
                                chunk.toString()
                            );
                            assertThat(body).doesNotContain("synthetic temperature policy");
                            var args = f.mapper
                                .createObjectNode()
                                .put("snapshotId", found.path("snapshotId").asText());
                            args.putArray("chunkIds").add(chunk.toString());
                            result
                                .put("outcome", "NEED_CONTEXT")
                                .put("explanation", "Read selected evidence")
                                .putArray("requests")
                                .addObject()
                                .put("loaderId", resolve.id())
                                .put("argumentsSchemaId", resolveDefinition.argumentsSchemaId())
                                .putNull("cursor")
                                .set("arguments", args);
                        }
                        case 3 -> {
                            assertThat(
                                request.at("/loadResults/2/data/evidence/0/text").asText()
                            ).isEqualTo(policyText);
                            result
                                .put("outcome", "PREPARED")
                                .put("explanation", "Prepare a disabled threshold rule");
                            var payload = f.mapper
                                .createObjectNode()
                                .put("name", "Synthetic temperature threshold")
                                .put("metric", "temperatureC")
                                .put("ruleType", "THRESHOLD")
                                .put("conditionType", "ABOVE_THRESHOLD")
                                .put("threshold", 80)
                                .put("enabled", false);
                            result.set(
                                "command",
                                f.mapper.valueToTree(
                                    new AssistantProtocol.Command(
                                        manifest.commandSchemaId(),
                                        manifest.skillVersion(),
                                        payload
                                    )
                                )
                            );
                        }
                        default -> throw new AssertionError("Unexpected model exchange");
                    }
                    return new com.voltcanary.aiagent.orchestration.routing.BudgetedModelCalls.Reply(
                        "JSON_TEXT",
                        result.toString(),
                        null
                    );
                }
            };
        var calls = new com.voltcanary.aiagent.orchestration.routing.RoutedModelCalls(
            f.mapper,
            f.validator,
            registry,
            ledger,
            f.traces,
            f.clock
        );
        var availability = org.mockito.Mockito.mock(
            com.voltcanary.aiagent.orchestration.routing.ModelRouting.Availability.class
        );
        org.mockito.Mockito.when(
            availability.authorized("fixture-provider", "fixture-model")
        ).thenReturn(true);
        org.mockito.Mockito.when(availability.outputModes("fixture-model")).thenReturn(
            java.util.Set.of("JSON_TEXT")
        );
        var routed = new com.voltcanary.aiagent.orchestration.plan.RoutedIntentModels(
            calls,
            requests,
            (i, s, p) ->
                new com.voltcanary.aiagent.orchestration.plan.RoutedIntentModels.Route(
                    f.mapper.valueToTree(policy),
                    new com.voltcanary.aiagent.orchestration.routing.RoutedModelCalls.Selection(
                        "primary",
                        p,
                        null,
                        100,
                        64
                    ),
                    skills.authorized(),
                    skills.capabilities(),
                    availability,
                    adapter
                ),
            store,
            f.validator,
            false,
            f.runner
        );
        var outcome = routed.step(input, f.session, UUID.randomUUID());
        assertThat(outcome).isInstanceOfSatisfying(
            AssistantProtocol.StepResponsePrepared.class,
            prepared -> {
                assertThat(prepared.command().payload().path("threshold").asInt()).isEqualTo(80);
                assertThat(prepared.command().payload().path("enabled").asBoolean()).isFalse();
            }
        );
        var reviewed = domain.prepare(
            input,
            manifest,
            ((AssistantProtocol.StepResponsePrepared) outcome).command(),
            f.session
        );
        var saved = f.reviews.find(reviewed.review().reviewId(), token.owner());
        assertThat(saved.status()).isEqualTo("READY");
        assertThat(saved.plan().actions())
            .singleElement()
            .satisfies(action -> {
                assertThat(action.parameters())
                    .containsEntry("threshold", 80)
                    .containsEntry("enabled", false)
                    .containsEntry("scopeType", "FLEET");
            });
        assertThat(journal.review(token.owner(), reviewed.review().reviewId())).isPresent();
        assertThat(count.get()).isEqualTo(3);
        var lifecycle = new com.voltcanary.aiagent.orchestration.plan.IntentReviewLifecycle(
            f.receipts
        );
        var awaitingConfirmation = lifecycle.attach(
            state,
            step.stepId(),
            manifest,
            reviewed,
            f.session
        );
        var committed = store.commit(preparedTurn, awaitingConfirmation, "Review ready", true);
        var restored = store.read(token.owner(), token.planId());
        assertThat(restored).isEqualTo(committed);
        assertThat(restored.status()).isEqualTo("WAITING_CONFIRMATION");
        assertThat(restored.step(step.stepId()).reviewId()).isEqualTo(
            reviewed.review().reviewId().toString()
        );
        assertThat(restored.reviews()).containsEntry(step.stepId(), reviewed.review());
        assertThat(
            f.reviews.find(reviewed.review().reviewId(), token.owner()).execution()
        ).isNull();
        return committed;
    }

    @Test
    void restartAndReplayKeepOneSnapshotAndOneJournalEntry() throws Exception {
        CollaborationStoreTest.verifyRestartAndReplay(fixture());
    }

    @Test
    void journalFailureRollsBackSnapshot() throws Exception {
        CollaborationStoreTest.verifyRollback(fixture());
    }

    @Test
    void requirementQuestionAndReviewInvalidationShareOneCommit() throws Exception {
        CollaborationStoreTest.verifyAtomicRequirements(fixture());
    }

    @Test
    void ownerCancelsWithoutClaimingAnotherRootTurn() throws Exception {
        CollaborationStoreTest.verifyCancellation(fixture());
    }

    @Test
    void graphQuestionBecomesAnAnswerableRequirement() throws Exception {
        CollaborationStoreTest.verifyQuestionPublication(fixture());
    }

    @Test
    void rootTurnAndOwnerFencesAreRequired() throws Exception {
        CollaborationStoreTest.verifyFences(fixture());
    }

    @Test
    void nativeSpecialistSubgraphSharesPostgresLedgerAndReplays() throws Exception {
        ConsultationWorkerTest.verify(fixture(), false);
    }

    @Test
    void unknownSpecialistExchangeKeepsPostgresReservation() throws Exception {
        ConsultationWorkerTest.verify(fixture(), true);
    }

    @Test
    void duplicateWorkerCannotCompleteTheFirstWorkersReservation() throws Exception {
        ConsultationWorkerTest.verify(fixture(), false, true);
    }

    @Test
    void leaseIsCheckedAfterWaitingForConversationLock() throws Exception {
        var fixture = fixture();
        var f = fixture.f();
        var locked = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var expiry = f.jdbc.queryForObject(
            "select clock_timestamp() + interval '1 second'",
            java.sql.Timestamp.class
        );
        f.jdbc.update(
            "update ai_orchestration_requests set lease_until=? where plan_id=?",
            expiry,
            fixture.turn().planId()
        );
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var blocker = pool.submit(() ->
                f.transactions.executeWithoutResult(status -> {
                    f.jdbc.queryForObject(
                        "select id from ai_conversations where id=? for update",
                        UUID.class,
                        fixture.turn().conversationId()
                    );
                    locked.countDown();
                    try {
                        if (
                            !release.await(10, java.util.concurrent.TimeUnit.SECONDS)
                        ) throw new IllegalStateException("FIXTURE_TIMEOUT");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interrupted);
                    }
                })
            );
            assertThat(locked.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var waiting = pool.submit(() ->
                fixture
                    .store()
                    .update(fixture.turn(), 0, "waiting", "a".repeat(64), (before, now) ->
                        CollaborationStoreTest.propose(fixture, before)
                    )
            );
            try {
                Thread.sleep(1500);
                assertThat(waiting.isDone()).isFalse();
            } finally {
                release.countDown();
            }
            blocker.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertThatThrownBy(() -> waiting.get(10, java.util.concurrent.TimeUnit.SECONDS))
                .hasCauseInstanceOf(
                    com.voltcanary.aiagent.orchestration.collaboration.Requirements.Rejected.class
                )
                .cause()
                .hasMessage("COLLABORATION_ROOT_TURN_CHANGED_OR_EXPIRED");
            assertThat(
                f.jdbc.queryForObject(
                    "select count(*) from ai_orchestration_collaboration",
                    Long.class
                )
            ).isZero();
        } finally {
            release.countDown();
        }
    }
}
