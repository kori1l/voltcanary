ALTER TABLE vcl_keyed_state ADD COLUMN journal_opened_at TIMESTAMP WITH TIME ZONE;

CREATE INDEX ix_vcl_keyed_epoch_opened
    ON vcl_keyed_state(account_id, rule_id, deployment_epoch, journal_opened_at);

CREATE INDEX ix_vcl_deployment_predecessor
    ON vcl_rule_deployments(account_id, predecessor_epoch, state, drain_before);
