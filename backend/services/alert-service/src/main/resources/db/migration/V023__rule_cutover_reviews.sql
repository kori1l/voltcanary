CREATE TABLE cfg_rule_cutover_review (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES cfg_rule_owner_fence(account_id),
    actor_id UUID NOT NULL,
    rule_id UUID NOT NULL,
    grant_id UUID NOT NULL REFERENCES cfg_automation_grant(id),
    grant_epoch BIGINT NOT NULL CHECK (grant_epoch > 0),
    predecessor_epoch UUID NOT NULL,
    successor_epoch UUID NOT NULL,
    bundle_id UUID NOT NULL,
    bundle_digest VARCHAR(64) NOT NULL,
    checkpoint_digest VARCHAR(64) NOT NULL,
    review_digest VARCHAR(64) NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    confirmed_at TIMESTAMP WITH TIME ZONE,
    CHECK (predecessor_epoch <> successor_epoch),
    UNIQUE (account_id, successor_epoch)
);
