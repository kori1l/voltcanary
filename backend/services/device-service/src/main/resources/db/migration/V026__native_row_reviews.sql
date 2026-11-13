CREATE TABLE cfg_row_review (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES accounts(id),
    actor_id UUID NOT NULL,
    dataset_id UUID NOT NULL REFERENCES cfg_dataset(id),
    schema_revision BIGINT NOT NULL,
    mutation_id UUID NOT NULL UNIQUE,
    created_record_id UUID NOT NULL UNIQUE,
    receipt_id UUID NOT NULL UNIQUE,
    reservation_key VARCHAR(160) NOT NULL,
    status VARCHAR(32) NOT NULL CHECK(status IN ('RESERVED','PREPARED','APPLYING','UNKNOWN','SUCCEEDED','FAILED','EXPIRED','CANCELLED')),
    revision BIGINT NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    canonical_document TEXT,
    document_digest VARCHAR(64),
    semantic_digest VARCHAR(64),
    review_digest VARCHAR(64),
    scope_digest VARCHAR(64) NOT NULL,
    outcome_code VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    UNIQUE(account_id,reservation_key)
);
CREATE TABLE cfg_row_receipt (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES accounts(id),
    review_id UUID NOT NULL UNIQUE REFERENCES cfg_row_review(id),
    operation VARCHAR(32) NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    payload_digest VARCHAR(64) NOT NULL,
    result_json TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    UNIQUE(account_id,operation,idempotency_key)
);
