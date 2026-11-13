CREATE TABLE cfg_evidence_policy_review (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES cfg_rule_owner_fence(account_id),
    actor_id UUID NOT NULL,
    grant_id UUID NOT NULL REFERENCES cfg_automation_grant(id),
    rule_id UUID NOT NULL,
    grant_epoch BIGINT NOT NULL CHECK(grant_epoch > 0),
    grant_definition_digest VARCHAR(64) NOT NULL,
    policy_id UUID NOT NULL UNIQUE,
    receipt_id UUID NOT NULL UNIQUE,
    key_hash VARCHAR(64) NOT NULL,
    reservation_key TEXT NOT NULL,
    revision BIGINT NOT NULL DEFAULT 1 CHECK(revision > 0),
    status VARCHAR(16) NOT NULL CHECK(status IN ('RESERVED','PREPARED','APPLYING','UNKNOWN','SUCCEEDED','FAILED','EXPIRED')),
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    canonical_definition TEXT,
    review_digest VARCHAR(64),
    outcome_code VARCHAR(80),
    UNIQUE(account_id,key_hash)
);
CREATE INDEX idx_evidence_policy_review_quota ON cfg_evidence_policy_review(account_id,status,expires_at);
CREATE TABLE cfg_evidence_policy (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES cfg_rule_owner_fence(account_id),
    actor_id UUID NOT NULL,
    rule_id UUID NOT NULL,
    grant_id UUID NOT NULL REFERENCES cfg_automation_grant(id),
    grant_epoch BIGINT NOT NULL CHECK(grant_epoch > 0),
    grant_definition_digest VARCHAR(64) NOT NULL,
    review_id UUID NOT NULL UNIQUE REFERENCES cfg_evidence_policy_review(id),
    canonical_definition TEXT NOT NULL,
    definition_digest VARCHAR(64) NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_evidence_policy_owner ON cfg_evidence_policy(account_id,grant_id);
