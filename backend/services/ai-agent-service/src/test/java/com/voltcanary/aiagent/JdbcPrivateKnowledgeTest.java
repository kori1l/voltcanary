package com.voltcanary.aiagent;

import static org.assertj.core.api.Assertions.assertThat;

import com.voltcanary.aiagent.adapter.JdbcPrivateKnowledge;
import com.voltcanary.aiagent.orchestration.collaboration.PrivateKnowledge;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class JdbcPrivateKnowledgeTest {

    static void verify(JdbcTemplate jdbc) {
        var adapter = new JdbcPrivateKnowledge(new NamedParameterJdbcTemplate(jdbc));
        var account = UUID.randomUUID();
        var foreign = UUID.randomUUID();
        var reader = UUID.randomUUID();
        var generation = UUID.randomUUID();
        var corpus = UUID.randomUUID();
        var source = UUID.randomUUID();
        var chunk = UUID.randomUUID();
        for (var owner : Set.of(account, foreign)) {
            jdbc.update(
                "INSERT INTO assistant_knowledge_source VALUES (?, ?, ?, 'v1', true)",
                owner,
                corpus,
                source
            );
            jdbc.update(
                "INSERT INTO assistant_knowledge_reader VALUES (?, ?, ?, ?)",
                owner,
                corpus,
                source,
                reader
            );
            jdbc.update(
                "INSERT INTO assistant_knowledge_chunk VALUES (?, ?, ?, 'v1', ?, ?)",
                owner,
                corpus,
                source,
                chunk,
                "temperature " + owner
            );
            jdbc.update(
                "INSERT INTO assistant_knowledge_vector VALUES (?, ?, ?, 'v1', ?, ?, '[1,0]')",
                owner,
                corpus,
                source,
                chunk,
                generation
            );
        }
        var scope = new PrivateKnowledge.Scope(account, reader, Set.of(corpus));
        var knowledge = new PrivateKnowledge(adapter, adapter, adapter);
        var requested = new PrivateKnowledge.Scope(
            account,
            reader,
            Set.of(corpus, UUID.randomUUID())
        );
        assertThat(knowledge.authorizeCorpora(requested)).containsExactly(corpus);
        var hits = knowledge.search(scope, "TEMPERATURE", 5);
        assertThat(hits).containsExactly(new PrivateKnowledge.Hit(corpus, source, "v1", chunk));
        assertThat(knowledge.search(scope, "temper", 5)).isEmpty();
        assertThat(knowledge.search(scope, source.toString(), 5)).isEqualTo(hits);
        assertThat(knowledge.search(scope, chunk.toString(), 5)).isEqualTo(hits);
        assertThat(adapter.searchVectorIds(scope, generation, new float[] { 1, 0 }, 5)).isEqualTo(
            hits
        );
        assertThat(
            adapter.searchVectorIds(scope, UUID.randomUUID(), new float[] { 1, 0 }, 5)
        ).isEmpty();
        assertThat(
            adapter.searchVectorIds(scope, generation, new float[] { 1, 0, 0 }, 5)
        ).isEmpty();
        assertThat(knowledge.search(scope, "missing temperature", 5)).isEmpty();
        var stale = new PrivateKnowledge.Hit(corpus, source, "obsolete", chunk);
        var candidates = java.util.List.of(stale, hits.getFirst());
        assertThat(adapter.authorizeCurrent(scope, candidates)).containsExactlyElementsOf(hits);
        assertThat(knowledge.resolve(scope, candidates))
            .extracting(PrivateKnowledge.Evidence::text)
            .containsExactly("temperature " + account);

        verifyRowSecurity(jdbc, scope);
        jdbc.update("DELETE FROM assistant_knowledge_reader WHERE account_id = ?", account);
        assertThat(knowledge.authorizeCorpora(requested)).isEmpty();
        assertThat(knowledge.search(scope, "temperature", 5)).isEmpty();
        assertThat(knowledge.search(scope, source.toString(), 5)).isEmpty();
        assertThat(knowledge.resolve(scope, hits)).isEmpty();
        assertThat(adapter.hydrate(scope, hits)).isEmpty();
        assertThat(adapter.searchVectorIds(scope, generation, new float[] { 1, 0 }, 5)).isEmpty();
    }

    private static void verifyRowSecurity(JdbcTemplate jdbc, PrivateKnowledge.Scope scope) {
        var otherCorpus = UUID.randomUUID();
        var otherSource = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO assistant_knowledge_source VALUES (?, ?, ?, 'v1', true)",
            scope.accountId(),
            otherCorpus,
            otherSource
        );
        jdbc.update(
            "INSERT INTO assistant_knowledge_reader VALUES (?, ?, ?, ?)",
            scope.accountId(),
            otherCorpus,
            otherSource,
            scope.principalId()
        );
        jdbc.update(
            "INSERT INTO assistant_knowledge_chunk VALUES (?, ?, ?, 'v1', ?, 'temperature other corpus')",
            scope.accountId(),
            otherCorpus,
            otherSource,
            UUID.randomUUID()
        );
        jdbc.execute("CREATE ROLE test_knowledge_reader NOLOGIN NOBYPASSRLS");
        jdbc.execute(
            """
            GRANT SELECT ON assistant_knowledge_chunk, assistant_knowledge_source, assistant_knowledge_reader
            TO test_knowledge_reader
            """
        );
        var ds = java.util.Objects.requireNonNull(jdbc.getDataSource());
        try (var connection = ds.getConnection()) {
            var scoped = new JdbcTemplate(
                new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection, true)
            );
            scoped.execute("SET ROLE test_knowledge_reader");
            assertThat(
                scoped.queryForList("SELECT chunk_text FROM assistant_knowledge_chunk")
            ).isEmpty();
            var adapter = new JdbcPrivateKnowledge(new NamedParameterJdbcTemplate(scoped));
            var hits = adapter.searchIds(scope, "temperature", 10);
            assertThat(adapter.hydrate(scope, hits))
                .extracting(PrivateKnowledge.Evidence::text)
                .containsExactly("temperature " + scope.accountId());
            assertThat(
                scoped.queryForList("SELECT chunk_text FROM assistant_knowledge_chunk")
            ).isEmpty();
            var tx = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                    scoped.getDataSource()
                )
            );
            tx.executeWithoutResult(status -> {
                scoped.queryForMap(
                    """
                    SELECT set_config('voltcanary.knowledge_account', ?, true) AS account,
                           set_config('voltcanary.knowledge_principal', ?, true) AS principal,
                           set_config('voltcanary.knowledge_corpora', ?, true) AS corpora
                    """,
                    scope.accountId().toString(),
                    scope.principalId().toString(),
                    "{" + scope.corpusIds().iterator().next() + "}"
                );
                assertThat(
                    scoped.queryForList(
                        "SELECT chunk_text FROM assistant_knowledge_chunk",
                        String.class
                    )
                ).containsExactly("temperature " + scope.accountId());
            });
        } catch (java.sql.SQLException failure) {
            throw new AssertionError(failure);
        }
    }
}
