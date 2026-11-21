CREATE INDEX assistant_knowledge_chunk_lexical_idx
    ON assistant_knowledge_chunk USING gin (to_tsvector('simple', chunk_text));

CREATE INDEX assistant_knowledge_chunk_source_lookup_idx
    ON assistant_knowledge_chunk (account_id, (source_id::text));

CREATE INDEX assistant_knowledge_chunk_id_lookup_idx
    ON assistant_knowledge_chunk (account_id, (chunk_id::text));
