CREATE TABLE cfg_native_schema_migration (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES accounts(id),
    actor_id UUID NOT NULL,
    dataset_id UUID NOT NULL REFERENCES cfg_dataset(id),
    reservation_key VARCHAR(160) NOT NULL,
    reserved_fields_json TEXT NOT NULL,
    new_generation UUID NOT NULL,
    physical_id UUID NOT NULL UNIQUE,
    receipt_id UUID NOT NULL UNIQUE,
    status VARCHAR(24) NOT NULL CHECK(status IN ('RESERVED','PREPARED','APPLYING','UNKNOWN','SUCCEEDED','CANCELLED','FAILED')),
    revision BIGINT NOT NULL,
    scope_digest VARCHAR(64) NOT NULL,
    expected_schema_revision BIGINT NOT NULL,
    expected_generation UUID NOT NULL,
    expected_data_version BIGINT NOT NULL,
    canonical_request TEXT,
    canonical_plan TEXT,
    request_digest VARCHAR(64),
    plan_digest VARCHAR(64),
    impact_json TEXT,
    impact_digest VARCHAR(64),
    review_digest VARCHAR(64),
    outcome_code VARCHAR(64),
    result_json TEXT,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    UNIQUE(account_id,reservation_key)
);
CREATE INDEX cfg_native_schema_migration_target ON cfg_native_schema_migration(account_id,dataset_id,status);

ALTER TABLE cfg_native_generation ADD COLUMN migration_review_id UUID UNIQUE REFERENCES cfg_native_schema_migration(id);
