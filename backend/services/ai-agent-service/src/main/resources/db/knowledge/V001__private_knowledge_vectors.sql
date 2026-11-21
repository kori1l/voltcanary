CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE assistant_knowledge_vector (
    account_id uuid NOT NULL,
    corpus_id uuid NOT NULL,
    source_id uuid NOT NULL,
    source_version varchar(128) NOT NULL,
    chunk_id uuid NOT NULL,
    generation_id uuid NOT NULL,
    embedding vector NOT NULL,
    PRIMARY KEY (account_id, corpus_id, source_id, source_version, chunk_id, generation_id),
    FOREIGN KEY (account_id, corpus_id, source_id, source_version, chunk_id)
        REFERENCES assistant_knowledge_chunk ON DELETE CASCADE
);

ALTER TABLE assistant_knowledge_vector ENABLE ROW LEVEL SECURITY;
ALTER TABLE assistant_knowledge_vector FORCE ROW LEVEL SECURITY;

CREATE POLICY assistant_knowledge_vector_read ON assistant_knowledge_vector FOR SELECT
    USING (EXISTS (
        SELECT 1 FROM assistant_knowledge_chunk c
        WHERE (c.account_id, c.corpus_id, c.source_id, c.source_version, c.chunk_id) =
              (assistant_knowledge_vector.account_id, assistant_knowledge_vector.corpus_id,
               assistant_knowledge_vector.source_id, assistant_knowledge_vector.source_version,
               assistant_knowledge_vector.chunk_id)
    ));
