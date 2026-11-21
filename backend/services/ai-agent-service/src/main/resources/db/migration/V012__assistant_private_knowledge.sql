CREATE TABLE assistant_knowledge_source (
    account_id uuid NOT NULL,
    corpus_id uuid NOT NULL,
    source_id uuid NOT NULL,
    source_version varchar(128) NOT NULL,
    active boolean NOT NULL,
    PRIMARY KEY (account_id, corpus_id, source_id),
    UNIQUE (account_id, corpus_id, source_id, source_version)
);

CREATE TABLE assistant_knowledge_reader (
    account_id uuid NOT NULL,
    corpus_id uuid NOT NULL,
    source_id uuid NOT NULL,
    principal_id uuid NOT NULL,
    PRIMARY KEY (account_id, corpus_id, source_id, principal_id),
    FOREIGN KEY (account_id, corpus_id, source_id)
        REFERENCES assistant_knowledge_source ON DELETE CASCADE
);

CREATE TABLE assistant_knowledge_chunk (
    account_id uuid NOT NULL,
    corpus_id uuid NOT NULL,
    source_id uuid NOT NULL,
    source_version varchar(128) NOT NULL,
    chunk_id uuid NOT NULL,
    chunk_text varchar(3000) NOT NULL,
    PRIMARY KEY (account_id, corpus_id, source_id, source_version, chunk_id),
    FOREIGN KEY (account_id, corpus_id, source_id, source_version)
        REFERENCES assistant_knowledge_source (account_id, corpus_id, source_id, source_version)
        ON DELETE CASCADE
);
