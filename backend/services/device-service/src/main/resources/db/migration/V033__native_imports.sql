CREATE TABLE cfg_import_artifact (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES accounts(id),
    actor_id UUID NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    byte_count INTEGER NOT NULL,
    content_base64 TEXT NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE(account_id,idempotency_key)
);
CREATE TABLE cfg_import_job (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES accounts(id),
    actor_id UUID NOT NULL,
    dataset_id UUID NOT NULL REFERENCES cfg_dataset(id),
    idempotency_key VARCHAR(160) NOT NULL,
    scope_digest VARCHAR(64) NOT NULL,
    document_digest VARCHAR(64) NOT NULL,
    canonical_document TEXT NOT NULL,
    review_digest VARCHAR(64) NOT NULL,
    canonical_plan TEXT NOT NULL,
    plan_digest VARCHAR(64) NOT NULL,
    total_rows INTEGER NOT NULL,
    valid_rows INTEGER NOT NULL,
    errors_json TEXT NOT NULL,
    status VARCHAR(24) NOT NULL CHECK(status IN ('PREPARED','REJECTED','SUCCEEDED','CANCELLED')),
    result_json TEXT,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE(account_id,idempotency_key)
);
CREATE INDEX cfg_import_artifact_expiry ON cfg_import_artifact(account_id,expires_at);
CREATE INDEX cfg_import_job_dataset ON cfg_import_job(account_id,dataset_id,id);
