ALTER TABLE vcl_rule_deployments ADD COLUMN paused_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE vcl_rule_deployments ADD COLUMN resume_from TIMESTAMP WITH TIME ZONE;
ALTER TABLE vcl_rule_deployments ADD CONSTRAINT ck_vcl_rule_pause
    CHECK (paused_at IS NULL OR (state='REVOKED' AND drain_before IS NULL));
CREATE INDEX ix_vcl_rule_pause ON vcl_rule_deployments(account_id,rule_id,paused_at);
