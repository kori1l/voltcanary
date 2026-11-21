ALTER TABLE assistant_knowledge_chunk ENABLE ROW LEVEL SECURITY;
ALTER TABLE assistant_knowledge_chunk FORCE ROW LEVEL SECURITY;

CREATE POLICY assistant_knowledge_chunk_read ON assistant_knowledge_chunk FOR SELECT
    USING (
        account_id = nullif(current_setting('voltcanary.knowledge_account', true), '')::uuid
        AND corpus_id = ANY (
            nullif(current_setting('voltcanary.knowledge_corpora', true), '')::uuid[]
        )
        AND EXISTS (
            SELECT 1
            FROM assistant_knowledge_source s
            JOIN assistant_knowledge_reader r USING (account_id, corpus_id, source_id)
            WHERE s.account_id = assistant_knowledge_chunk.account_id
              AND s.corpus_id = assistant_knowledge_chunk.corpus_id
              AND s.source_id = assistant_knowledge_chunk.source_id
              AND s.source_version = assistant_knowledge_chunk.source_version
              AND s.active
              AND r.principal_id = nullif(current_setting('voltcanary.knowledge_principal', true), '')::uuid
        )
    );
