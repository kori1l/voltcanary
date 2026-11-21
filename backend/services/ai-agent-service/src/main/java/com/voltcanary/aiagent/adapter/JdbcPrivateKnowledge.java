package com.voltcanary.aiagent.adapter;

import com.voltcanary.aiagent.orchestration.collaboration.PrivateKnowledge;
import com.voltcanary.aiagent.orchestration.collaboration.PrivateKnowledge.Evidence;
import com.voltcanary.aiagent.orchestration.collaboration.PrivateKnowledge.Hit;
import com.voltcanary.aiagent.orchestration.collaboration.PrivateKnowledge.Scope;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Every read joins the current source and its principal grant in the same SQL statement. */
public final class JdbcPrivateKnowledge implements PrivateKnowledge.CandidateIndex,
        PrivateKnowledge.OwnerAccess, PrivateKnowledge.EvidenceResolver {
    private static final String IDS = "c.corpus_id, c.source_id, c.source_version, c.chunk_id";
    private static final String SCOPED = """
            FROM assistant_knowledge_chunk c
            JOIN assistant_knowledge_source s USING (account_id, corpus_id, source_id, source_version)
            JOIN assistant_knowledge_reader r USING (account_id, corpus_id, source_id)
            WHERE c.account_id = :account AND r.principal_id = :principal AND s.active = true
              AND c.corpus_id IN (:corpora)
            """;
    private static final String EXACT = """
              AND (c.corpus_id, c.source_id, c.source_version, c.chunk_id) IN (:hits)
            """;
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate reads;

    public JdbcPrivateKnowledge(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
        var dataSource = java.util.Objects.requireNonNull(jdbc.getJdbcTemplate().getDataSource());
        this.reads = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        this.reads.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.reads.setReadOnly(true);
    }

    @Override
    public List<Hit> searchIds(Scope scope, String query, int limit) {
        if (scope.corpusIds().isEmpty()) return List.of();
        return scopedRead(scope, () -> jdbc.query("""
                WITH query AS (SELECT plainto_tsquery('simple', :query) AS terms)
                SELECT
                """ + IDS + " " + SCOPED + """
                  AND (c.source_id::text = :query OR c.chunk_id::text = :query
                       OR to_tsvector('simple', c.chunk_text) @@ (SELECT terms FROM query))
                ORDER BY CASE WHEN c.source_id::text = :query OR c.chunk_id::text = :query THEN 0 ELSE 1 END,
                         ts_rank_cd(to_tsvector('simple', c.chunk_text), (SELECT terms FROM query)) DESC,
                         c.corpus_id, c.source_id, c.chunk_id
                LIMIT :limit
                """, Map.of("account", scope.accountId(), "principal", scope.principalId(),
                "corpora", scope.corpusIds(), "query", query, "limit", Math.min(limit, 40)), (rs, row) -> hit(rs)));
    }

    @Override
    public Set<UUID> authorizeCorpora(Scope scope) {
        if (scope.corpusIds().isEmpty()) return Set.of();
        return Set.copyOf(scopedRead(scope, () -> jdbc.query("""
                SELECT DISTINCT s.corpus_id
                FROM assistant_knowledge_source s
                JOIN assistant_knowledge_reader r USING (account_id, corpus_id, source_id)
                WHERE s.account_id = :account AND r.principal_id = :principal AND s.active
                  AND s.corpus_id IN (:corpora)
                """, Map.of("account", scope.accountId(), "principal", scope.principalId(),
                "corpora", scope.corpusIds()), (rs, row) -> rs.getObject("corpus_id", UUID.class))));
    }

    @Override
    public Set<Hit> authorizeCurrent(Scope scope, List<Hit> candidates) {
        if (scope.corpusIds().isEmpty() || candidates.isEmpty()) return Set.of();
        return scopedRead(scope, () -> Set.copyOf(jdbc.query("SELECT " + IDS + " " + SCOPED + EXACT,
                parameters(scope, candidates), (rs, row) -> hit(rs))));
    }

    @Override
    public List<Hit> searchVectorIds(Scope scope, UUID generationId, float[] embedding, int limit) {
        if (embedding.length == 0 || embedding.length > 16000 || limit < 1 || limit > 40) {
            throw new IllegalArgumentException("INVALID_KNOWLEDGE_VECTOR_QUERY");
        }
        var vector = new java.util.StringJoiner(",", "[", "]");
        boolean nonzero = false;
        for (float value : embedding) {
            if (!Float.isFinite(value)) throw new IllegalArgumentException("INVALID_KNOWLEDGE_VECTOR_QUERY");
            nonzero |= value != 0;
            vector.add(Float.toString(value));
        }
        if (!nonzero) throw new IllegalArgumentException("INVALID_KNOWLEDGE_VECTOR_QUERY");
        if (scope.corpusIds().isEmpty()) return List.of();
        return scopedRead(scope, () -> jdbc.query("SELECT " + IDS + " " + SCOPED + """
                  AND EXISTS (
                    SELECT 1 FROM assistant_knowledge_vector v
                    WHERE (v.account_id, v.corpus_id, v.source_id, v.source_version, v.chunk_id) =
                          (c.account_id, c.corpus_id, c.source_id, c.source_version, c.chunk_id)
                      AND v.generation_id = :generation
                      AND vector_dims(v.embedding) = :dimensions AND vector_norm(v.embedding) > 0
                  )
                ORDER BY (
                    SELECT CASE WHEN vector_dims(v.embedding) = :dimensions AND vector_norm(v.embedding) > 0
                                THEN v.embedding <=> CAST(:vector AS vector) END
                    FROM assistant_knowledge_vector v
                    WHERE (v.account_id, v.corpus_id, v.source_id, v.source_version, v.chunk_id) =
                          (c.account_id, c.corpus_id, c.source_id, c.source_version, c.chunk_id)
                      AND v.generation_id = :generation
                ), c.corpus_id, c.source_id, c.chunk_id
                LIMIT :limit
                """, Map.of("account", scope.accountId(), "principal", scope.principalId(), "corpora", scope.corpusIds(),
                "generation", generationId, "dimensions", embedding.length, "vector", vector.toString(),
                "limit", limit), (rs, row) -> hit(rs)));
    }

    @Override
    public List<Evidence> hydrate(Scope scope, List<Hit> authorized) {
        if (scope.corpusIds().isEmpty() || authorized.isEmpty()) return List.of();
        return scopedRead(scope, () -> jdbc.query("SELECT " + IDS + ", c.chunk_text " + SCOPED + EXACT,
                parameters(scope, authorized), (rs, row) -> new Evidence(hit(rs), rs.getString("chunk_text"))));
    }

    private <T> T scopedRead(Scope scope, Supplier<T> query) {
        String corpora = scope.corpusIds().stream().map(UUID::toString).collect(Collectors.joining(",", "{", "}"));
        return reads.execute(status -> {
            jdbc.queryForMap("""
                    SELECT set_config('voltcanary.knowledge_account', :account, true) AS account,
                           set_config('voltcanary.knowledge_principal', :principal, true) AS principal,
                           set_config('voltcanary.knowledge_corpora', :corpora, true) AS corpora
                    """, Map.of("account", scope.accountId().toString(), "principal", scope.principalId().toString(),
                    "corpora", corpora));
            return query.get();
        });
    }

    private static Map<String, Object> parameters(Scope scope, List<Hit> hits) {
        var tuples = hits.stream()
                .map(hit -> new Object[] {hit.corpusId(), hit.sourceId(), hit.sourceVersion(), hit.chunkId()})
                .toList();
        return Map.of("account", scope.accountId(), "principal", scope.principalId(),
                "corpora", scope.corpusIds(), "hits", tuples);
    }

    private static Hit hit(ResultSet row) throws SQLException {
        return new Hit(row.getObject("corpus_id", UUID.class), row.getObject("source_id", UUID.class),
                row.getString("source_version"), row.getObject("chunk_id", UUID.class));
    }
}
