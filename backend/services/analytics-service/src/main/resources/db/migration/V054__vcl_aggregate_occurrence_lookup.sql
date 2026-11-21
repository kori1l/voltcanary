CREATE INDEX idx_vcl_keyed_state_occurrence
    ON vcl_keyed_state(account_id, rule_id, deployment_epoch, occurrence_digest, key_digest);
