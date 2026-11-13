CREATE TABLE cfg_grant_review (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES cfg_rule_owner_fence(account_id),
    actor_id UUID NOT NULL,
    rule_review_id UUID NOT NULL REFERENCES cfg_rule_review_reservation(id),
    rule_id UUID NOT NULL,
    grant_id UUID NOT NULL UNIQUE,
    receipt_id UUID NOT NULL UNIQUE,
    reservation_key_hash VARCHAR(64) NOT NULL,
    reservation_key TEXT NOT NULL,
    reservation_digest VARCHAR(64) NOT NULL,
    revision BIGINT NOT NULL DEFAULT 1,
    status VARCHAR(16) NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    canonical_definition TEXT,
    canonical_pins TEXT,
    review_digest VARCHAR(64),
    outcome_code VARCHAR(80),
    UNIQUE(account_id,reservation_key_hash),
    CHECK (status IN ('RESERVED','PREPARED','APPLYING','UNKNOWN','SUCCEEDED','FAILED','EXPIRED','CANCELLED'))
);

CREATE TABLE cfg_automation_grant (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES cfg_rule_owner_fence(account_id),
    actor_id UUID NOT NULL,
    rule_id UUID NOT NULL,
    review_id UUID NOT NULL UNIQUE REFERENCES cfg_grant_review(id),
    canonical_definition TEXT NOT NULL,
    canonical_pins TEXT NOT NULL,
    definition_digest VARCHAR(64) NOT NULL,
    epoch BIGINT NOT NULL CHECK (epoch > 0),
    status VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE','REVOKED')),
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE cfg_grant_revocation (
    id UUID PRIMARY KEY,
    receipt_id UUID NOT NULL UNIQUE,
    grant_id UUID NOT NULL REFERENCES cfg_automation_grant(id),
    account_id UUID NOT NULL REFERENCES cfg_rule_owner_fence(account_id),
    actor_id UUID NOT NULL,
    expected_epoch BIGINT NOT NULL CHECK (expected_epoch > 0),
    key_hash VARCHAR(64) NOT NULL,
    idempotency_key TEXT NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING','UNKNOWN','SUCCEEDED','FAILED')),
    result_epoch BIGINT,
    UNIQUE(account_id,key_hash)
);

CREATE INDEX idx_cfg_grant_review_quota ON cfg_grant_review(account_id,status,expires_at);
CREATE INDEX idx_cfg_grant_rule ON cfg_automation_grant(account_id,rule_id,status);
