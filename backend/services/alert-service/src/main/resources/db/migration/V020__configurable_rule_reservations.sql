CREATE TABLE cfg_rule_owner_fence (account_id UUID PRIMARY KEY);

CREATE TABLE cfg_rule_review_reservation (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES cfg_rule_owner_fence(account_id),
    actor_id UUID NOT NULL,
    rule_id UUID NOT NULL UNIQUE,
    receipt_id UUID NOT NULL UNIQUE,
    reservation_key_hash VARCHAR(64) NOT NULL,
    reservation_key TEXT NOT NULL,
    request_digest VARCHAR(64) NOT NULL,
    output_ids_json TEXT NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('RESERVED','EXPIRED','CANCELLED')),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE(account_id, reservation_key_hash)
);

CREATE INDEX idx_cfg_rule_reservation_quota ON cfg_rule_review_reservation(account_id,status,expires_at);
