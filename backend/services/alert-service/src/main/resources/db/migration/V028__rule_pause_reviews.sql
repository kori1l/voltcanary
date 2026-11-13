CREATE TABLE cfg_rule_pause_review (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES cfg_rule_owner_fence(account_id),
    actor_id UUID NOT NULL,
    rule_id UUID NOT NULL,
    grant_id UUID NOT NULL REFERENCES cfg_automation_grant(id),
    deployment_epoch UUID NOT NULL,
    deployment_revision BIGINT NOT NULL CHECK (deployment_revision > 0),
    bundle_digest VARCHAR(64) NOT NULL,
    review_digest VARCHAR(64) NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    confirmed_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX ix_cfg_rule_pause_review_owner ON cfg_rule_pause_review(account_id,actor_id,expires_at);
