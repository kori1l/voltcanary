CREATE TABLE cfg_collection_review (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES accounts(id),
    actor_id UUID NOT NULL,
    resource_id UUID NOT NULL,
    generation UUID NOT NULL,
    record_id_field_id UUID NOT NULL,
    receipt_id UUID NOT NULL UNIQUE,
    field_ids_json TEXT NOT NULL,
    reservation_key VARCHAR(160) NOT NULL,
    reservation_digest VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL CHECK(status IN ('RESERVED','PREPARED','APPLYING','SUCCEEDED','UNKNOWN','CANCELLED','EXPIRED')),
    revision BIGINT NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    canonical_document TEXT,
    semantic_digest VARCHAR(64),
    review_digest VARCHAR(64),
    scope_digest VARCHAR(64) NOT NULL,
    apply_key VARCHAR(160),
    outcome_code VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    UNIQUE(account_id,reservation_key),
    UNIQUE(account_id,apply_key)
);
CREATE TABLE cfg_mutation_receipt (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES accounts(id),
    review_id UUID NOT NULL UNIQUE REFERENCES cfg_collection_review(id),
    operation VARCHAR(32) NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    payload_digest VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    result_json TEXT,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    UNIQUE(account_id,operation,idempotency_key)
);
CREATE TABLE cfg_outbox (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES accounts(id),
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload_json TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    published_at TIMESTAMP WITH TIME ZONE
);
