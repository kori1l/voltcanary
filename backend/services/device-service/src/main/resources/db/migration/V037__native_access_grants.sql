CREATE TABLE cfg_native_access_review (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES accounts(id),
    actor_id UUID NOT NULL,
    dataset_id UUID NOT NULL REFERENCES cfg_dataset(id),
    workspace_id UUID NOT NULL,
    grant_id UUID NOT NULL UNIQUE,
    receipt_id UUID NOT NULL UNIQUE,
    reservation_key VARCHAR(160) NOT NULL,
    scope_digest VARCHAR(64) NOT NULL,
    canonical_definition TEXT NOT NULL,
    definition_digest VARCHAR(64) NOT NULL,
    review_digest VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK(status IN ('PREPARED','SUCCEEDED','CANCELLED')),
    expected_schema_revision BIGINT NOT NULL,
    expected_generation UUID NOT NULL,
    expected_data_version BIGINT NOT NULL,
    association_version BIGINT NOT NULL,
    membership_digest VARCHAR(64) NOT NULL,
    result_json TEXT,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE(account_id,reservation_key)
);

CREATE TABLE cfg_native_access_grant (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES accounts(id),
    workspace_id UUID NOT NULL,
    dataset_id UUID NOT NULL REFERENCES cfg_dataset(id),
    schema_revision BIGINT NOT NULL,
    subject_kind VARCHAR(8) NOT NULL CHECK(subject_kind IN ('USER','TEAM')),
    subject_id UUID NOT NULL,
    canonical_policy TEXT NOT NULL,
    policy_digest VARCHAR(64) NOT NULL,
    revision BIGINT NOT NULL CHECK(revision>0),
    status VARCHAR(16) NOT NULL CHECK(status IN ('ACTIVE','REVOKED')),
    review_id UUID NOT NULL UNIQUE REFERENCES cfg_native_access_review(id),
    created_by UUID NOT NULL,
    revoked_key VARCHAR(160),
    revoked_result TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    FOREIGN KEY(workspace_id,account_id) REFERENCES workspace_resource_accounts(workspace_id,account_id)
);
CREATE INDEX cfg_native_access_grant_subject ON cfg_native_access_grant(workspace_id,account_id,subject_kind,subject_id,status);
CREATE INDEX cfg_native_access_grant_dataset ON cfg_native_access_grant(account_id,dataset_id,status);
CREATE INDEX cfg_native_access_review_account ON cfg_native_access_review(account_id,status,expires_at);
